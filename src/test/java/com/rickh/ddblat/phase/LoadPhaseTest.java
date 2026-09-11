package com.rickh.ddblat.phase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class LoadPhaseTest {

    @TempDir Path tmp;

    private static final LoadPhase.TableIdentity TABLE_A =
        new LoadPhase.TableIdentity("smoke-table", 1_700_000_000_000L);

    @Test
    void coversEveryIndexExactlyOnce() {
        LoadPhase.Source src = LoadPhase.source(TABLE_A, 1024, tmp.resolve("ck"), 0, 1);
        BitSet seen = new BitSet(1024);
        int v;
        while ((v = src.next()) >= 0) {
            assertThat(seen.get(v)).as("index %d handed out twice", v).isFalse();
            seen.set(v);
        }
        assertThat(seen.cardinality()).isEqualTo(1024);
    }

    @Test
    void multipleClientsPartitionTheKeySpaceWithoutOverlap() {
        LoadPhase.Source a = LoadPhase.source(TABLE_A, 1024, tmp.resolve("ca"), 0, 2);
        LoadPhase.Source b = LoadPhase.source(TABLE_A, 1024, tmp.resolve("cb"), 1, 2);
        BitSet seen = new BitSet(1024);
        int v;
        while ((v = a.next()) >= 0) { assertThat(seen.get(v)).isFalse(); seen.set(v); }
        while ((v = b.next()) >= 0) { assertThat(seen.get(v)).isFalse(); seen.set(v); }
        assertThat(seen.cardinality()).isEqualTo(1024);
        assertThat(a.totalForThisClient()).isEqualTo(512L);
    }

    @Test
    void checkpointIsConservativeSoResumeNeverSkipsUnwrittenWork() throws Exception {
        Path ck = tmp.resolve("ck2");
        LoadPhase.Source first = LoadPhase.source(TABLE_A, 4096, ck, 0, 1);
        for (int i = 0; i < 3000; i++) first.next();
        first.checkpoint(3000);            // 3000 acknowledged

        LoadPhase.Source resumed = LoadPhase.source(TABLE_A, 4096, ck, 0, 1);
        long from = resumed.resumedFromIndex();

        // The watermark trails acknowledged work by the safety margin: it may redo,
        // it must never skip. PutItem is idempotent, so redoing is free.
        assertThat(from).isLessThanOrEqualTo(3000L);
        assertThat(from).isGreaterThan(0L);

        BitSet seen = new BitSet(4096);
        int v;
        while ((v = resumed.next()) >= 0) seen.set(v);
        for (int i = (int) from; i < 4096; i++) {
            assertThat(seen.get(i)).as("index %d must not be skipped on resume", i).isTrue();
        }
    }

    @Test
    void checkpointOfZeroCompletedResumesFromTheBeginning() throws Exception {
        Path ck = tmp.resolve("ck3");
        LoadPhase.Source src = LoadPhase.source(TABLE_A, 1024, ck, 0, 1);
        src.checkpoint(0);
        assertThat(LoadPhase.source(TABLE_A, 1024, ck, 0, 1).resumedFromIndex()).isZero();
    }

    @Test
    void anAbsentCheckpointStartsFromZero() {
        assertThat(LoadPhase.source(TABLE_A, 1024, tmp.resolve("nope"), 0, 1).resumedFromIndex())
            .isZero();
    }

    /**
     * The original sequential test (next() 3000 times, then checkpoint(3000)) has zero gap
     * between handed-out and acknowledged work, so it stays green even with the safety-margin
     * subtraction deleted entirely. This test creates the gap the margin actually exists for:
     * completedCount is a simple counter of finished work -- exactly like
     * WorkerPool.completed -- not a high-water mark of which index is done, so it can reach K
     * while a lower-numbered index handed out earlier is still in flight (its owning thread
     * is slow; other threads raced ahead, finished later-numbered work, and incremented the
     * counter without it).
     */
    @Test
    void checkpointMarginProtectsAnInFlightIndexAcknowledgedOutOfOrder() throws Exception {
        int itemCount = 8192;
        Path ck = tmp.resolve("ck-inflight");
        LoadPhase.Source src = LoadPhase.source(TABLE_A, itemCount, ck, 0, 1);

        // Deterministically advance the cursor to index 3000, single-threaded.
        for (int i = 0; i < 3000; i++) src.next();
        int inFlightIndex = src.next();                     // index 3000: the "slow" request
        assertThat(inFlightIndex).isEqualTo(3000);

        // completedCount already reflects indices 0..2999 as acknowledged (in order). The
        // slow request holding index 3000 does NOT increment it.
        AtomicLong completed = new AtomicLong(3000);

        // Several "fast" threads race ahead of the still-outstanding index 3000, pulling
        // and immediately acknowledging 500 further indices (3001..3500) out of order with
        // respect to it -- completedCount reaches 3500 while index 3000 remains in flight.
        // The gap (500) is comfortably inside the safety margin (1024) but the index is
        // still well below completedCount, so an unprotected watermark would skip it.
        int fastThreads = 4;
        int perThread = 125;
        Thread[] threads = new Thread[fastThreads];
        for (int t = 0; t < fastThreads; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    int idx = src.next();
                    if (idx < 0) break;
                    completed.incrementAndGet();
                }
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();

        long completedCount = completed.get();
        assertThat(completedCount).isEqualTo(3500L);

        src.checkpoint(completedCount);

        LoadPhase.Source resumed = LoadPhase.source(TABLE_A, itemCount, ck, 0, 1);
        long from = resumed.resumedFromIndex();
        // The margin must trail completedCount by enough to sit at or below the still
        // in-flight index -- if it doesn't, this next assertion is what a deleted margin
        // would violate, not this one directly, but a resumeFrom this far above the
        // in-flight index would make the redo assertion below fail. Kept for diagnostics.
        assertThat(from).isLessThanOrEqualTo((long) inFlightIndex);

        BitSet seen = new BitSet(itemCount);
        int v;
        while ((v = resumed.next()) >= 0) seen.set(v);
        assertThat(seen.get(inFlightIndex))
            .as("in-flight index %d (acknowledged out of order, still outstanding at "
                + "checkpoint time) must be redone on resume, not skipped", inFlightIndex)
            .isTrue();
    }

    /**
     * Task-17's actual root cause, reproduced directly: a checkpoint written against one
     * table instance must never be honored against a different one, even under the identical
     * name and item count -- CreationDateTime is the one field that changes when a table is
     * deleted and recreated, which is exactly what happens between smoke runs. Without the
     * identity check, this resumes at watermark 15360 against a fresh 16384-item table and
     * loads only the last 1024 items -- silently, with no error -- which is precisely how the
     * field incident's read phases ended up measuring latency against a ~93%-empty table.
     */
    @Test
    void aCheckpointFromADifferentTableInstanceIsDiscardedAndLoggedLoudly() throws Exception {
        Path ck = tmp.resolve("ck-stale");
        LoadPhase.Source original = LoadPhase.source(TABLE_A, 16384, ck, 0, 1);
        for (int i = 0; i < 15360; i++) original.next();
        original.checkpoint(15360);
        assertThat(Files.readString(ck)).contains("smoke-table").contains("14336");

        // Same table NAME, same item count, but a different CreationDateTime -- exactly what
        // "delete the table, recreate it under the same name" produces.
        LoadPhase.TableIdentity recreatedTable =
            new LoadPhase.TableIdentity("smoke-table", 1_700_000_099_000L);

        PrintStream realErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        LoadPhase.Source resumed;
        try {
            System.setErr(new PrintStream(capturedErr, true));
            resumed = LoadPhase.source(recreatedTable, 16384, ck, 0, 1);
        } finally {
            System.setErr(realErr);
        }

        assertThat(resumed.resumedFromIndex())
            .as("a checkpoint from a different table instance must never be honored")
            .isZero();

        String logged = capturedErr.toString();
        assertThat(logged)
            .as("the invalidation must be logged loudly, not silently discarded: %s", logged)
            .contains("CHECKPOINT-INVALID")
            .contains("smoke-table");

        // And the full key space is covered from zero -- no holes, no silent partial load.
        BitSet seen = new BitSet(16384);
        int v;
        while ((v = resumed.next()) >= 0) seen.set(v);
        assertThat(seen.cardinality()).isEqualTo(16384);
    }

    /** Same identity mismatch, via itemCount instead of table recreation. */
    @Test
    void aCheckpointWrittenForADifferentItemCountIsDiscarded() throws Exception {
        Path ck = tmp.resolve("ck-itemcount");
        LoadPhase.Source original = LoadPhase.source(TABLE_A, 8192, ck, 0, 1);
        original.checkpoint(8192);

        LoadPhase.Source resumed = LoadPhase.source(TABLE_A, 16384, ck, 0, 1);
        assertThat(resumed.resumedFromIndex()).isZero();
    }

    @Test
    void aPreExistingBareIntegerCheckpointIsTreatedAsUntrustedNotAsARawWatermark() throws Exception {
        // Pre-task-17 checkpoint format: a bare watermark with no identity fields at all.
        Path ck = tmp.resolve("ck-legacy");
        Files.writeString(ck, "15360");

        assertThat(LoadPhase.source(TABLE_A, 16384, ck, 0, 1).resumedFromIndex()).isZero();
    }

    @Test
    void matchingIdentityAndItemCountResumesNormally() throws Exception {
        Path ck = tmp.resolve("ck-match");
        LoadPhase.Source first = LoadPhase.source(TABLE_A, 4096, ck, 0, 1);
        first.checkpoint(3000);

        LoadPhase.Source resumed = LoadPhase.source(TABLE_A, 4096, ck, 0, 1);
        assertThat(resumed.resumedFromIndex()).isEqualTo(3000L - 1024L);
    }
}
