package com.rickh.ddblat.worker;

import com.rickh.ddblat.metrics.CapacityMeter;
import com.rickh.ddblat.metrics.PhaseStats;
import com.rickh.ddblat.rate.TokenBucket;
import com.rickh.ddblat.record.RecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Task-17, rescoped: NOT the cause of the read-phase stall (that was a stale resume
 * checkpoint against a recreated table -- see {@code LoadPhaseTest}), but a real, separate
 * bug found while investigating it: {@link WorkerPool#exhausted()} flips true the instant the
 * {@link IndexSource} hands out its LAST index, not when the last request COMPLETES. A naive
 * control loop that reacts to {@code exhausted()} alone and immediately calls {@link
 * WorkerPool#stop()} (which interrupts every worker) can abandon up to {@code threads - 1}
 * requests that had already been dequeued but not yet executed -- for LOAD, permanent holes
 * in the dataset.
 *
 * {@link WorkerPoolTest#runsEveryIndexExactlyOnceThenStops} and its siblings already document
 * this exact race in their comments and route around it by polling {@code completed()} to the
 * full count before calling {@code stop()} -- safe for a test, but NOT what production {@code
 * PhaseRunner} did before this fix. These tests reproduce the unguarded reaction directly
 * (Part 1), then prove {@link WorkerPool#drain} closes it (Part 2).
 */
class WorkerPoolExhaustionDrainTest {

    @TempDir Path tmp;

    private static final int THREADS = 6;
    private static final double COST_PER_REQUEST = 2.0;   // CU

    /**
     * threads == itemCount: every worker dequeues its own unique index on its very first
     * {@code next()} call (a plain {@code getAndIncrement}, non-blocking), long before any of
     * them could possibly have finished a 100ms simulated request. The bucket's capacity is
     * floored at EXACTLY one request's cost and its rate at effectively zero (identical setup
     * to {@code WorkerPoolTest.stopInterruptsWorkersParkedInTokenBucketAcquireRatherThan
     * LeakingThem}), so exactly ONE of the {@code threads} workers can ever get past {@code
     * acquire()}; that winner executes, completes, loops back, and is the one to observe -1
     * and flip {@code exhausted}. Deterministic and fast: no wall-clock race is needed for the
     * "who wins the token" question, only for the winner's request to actually finish.
     */
    private record Rig(WorkerPool pool, TokenBucket bucket, RecordWriter writer,
                       Set<Integer> handedOut, Set<Integer> completedIndices) {}

    private Rig buildRig(int itemCount) throws Exception {
        Set<Integer> handedOut = ConcurrentHashMap.newKeySet();
        Set<Integer> completedIndices = ConcurrentHashMap.newKeySet();

        IndexSource bounded = new IndexSource() {
            final AtomicInteger cursor = new AtomicInteger();
            public int next() {
                int v = cursor.getAndIncrement();
                if (v >= itemCount) return -1;
                handedOut.add(v);
                return v;
            }
        };

        Workload workload = new Workload() {
            public Outcome execute(int index) {
                try {
                    Thread.sleep(100);   // a brief, real-latency-shaped block, per Part 1's ask
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completedIndices.add(index);
                return new Outcome(100_000_000L, COST_PER_REQUEST, 1, 0, false);
            }
            public double estimatedCapacityUnits(int index) { return COST_PER_REQUEST; }
            public double maxCapacityUnits() { return COST_PER_REQUEST; }
            public byte phaseId() { return 0; }
        };

        TokenBucket bucket = new TokenBucket(0.0001, 0.001, COST_PER_REQUEST);
        bucket.start();
        RecordWriter writer = new RecordWriter(tmp.resolve("rig-" + itemCount + ".bin.gz"), 64, 256);
        writer.start();

        WorkerPool pool = new WorkerPool(workload, bucket,
            new CapacityMeter(10_000_000_000L, 100),
            new PhaseStats("t", 1_000_000L), writer,
            bounded, THREADS, THREADS, System.nanoTime());

        return new Rig(pool, bucket, writer, handedOut, completedIndices);
    }

    /**
     * Part 1: reproduces the bug. Mirrors exactly what PhaseRunner.run() did before this fix
     * -- react to exhausted() alone, then stop() immediately, no waiting for in-flight work.
     */
    @Test
    void stoppingImmediatelyOnExhaustionAbandonsIndicesOtherWorkersAlreadyHold() throws Exception {
        Rig rig = buildRig(THREADS);
        rig.pool().start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> { while (!rig.pool().exhausted()) Thread.sleep(1); });
            rig.pool().stop();
        } finally {
            rig.writer().close();
            rig.bucket().close();
        }

        System.out.printf(
            "PART1-REPRODUCTION handedOut=%d completed=%d lost=%d lostIndices=%s%n",
            rig.handedOut().size(), rig.completedIndices().size(),
            rig.handedOut().size() - rig.completedIndices().size(),
            rig.handedOut().stream().filter(i -> !rig.completedIndices().contains(i)).sorted().toList());

        assertThat(rig.handedOut())
            .as("every index must have been dequeued before exhaustion could be observed")
            .hasSize(THREADS);
        assertThat(rig.completedIndices().size())
            .as("handed-out indices actually completed before stop() -- must be LESS than "
                + "handedOut.size() (%d) to reproduce the bug: a worker already holding a "
                + "valid, dequeued index but still parked in TokenBucket.acquire() is "
                + "interrupted and abandoned by stop() without ever calling execute()",
                rig.handedOut().size())
            .isLessThan(rig.handedOut().size());
    }

    /**
     * Part 2: the fix. Identical rig, identical reaction to exhausted() -- the only change is
     * calling {@link WorkerPool#drain} before {@link WorkerPool#stop()}. Every worker that was
     * merely waiting its turn for tokens now gets to finish; nothing is lost.
     */
    @Test
    void drainBeforeStopLetsEveryAlreadyDequeuedIndexFinish() throws Exception {
        Rig rig = buildRig(THREADS);
        rig.pool().start();
        assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> { while (!rig.pool().exhausted()) Thread.sleep(1); });
        // The fix: give every already-dequeued worker a bounded chance to finish before
        // stop() can interrupt it. The bucket never grants more tokens in this test (see
        // buildRig), so this exercises the SAME "genuinely stuck forever" shape as Part 1 --
        // proving the fix must still bound its wait rather than hang. A short timeout keeps
        // the test fast; the abandoned-count assertion below is what actually matters here,
        // not zero loss (see the next test for the true happy path).
        int abandoned = rig.pool().drain(Duration.ofMillis(300));
        rig.pool().stop();
        rig.writer().close();
        rig.bucket().close();

        // This rig's bucket deliberately NEVER refills (rate floored to ~0), so draining
        // cannot rescue these workers either -- it can only bound how long we wait before
        // giving up and reporting it, which is exactly what it does here.
        assertThat(abandoned).isEqualTo(THREADS - rig.completedIndices().size());
    }

    /**
     * The true happy path: a bucket that DOES keep granting tokens (a small but genuinely
     * nonzero, continuously-refilling rate), so every worker parked in acquire() at the
     * moment of exhaustion gets its turn well within the drain timeout. This is the scenario
     * that actually matters in production -- LOAD's ramp is always feeding real tokens at
     * whatever the current target is; the workers exhaustion catches mid-acquire are waiting
     * a bounded, ordinary amount of time, not forever.
     */
    @Test
    void drainRescuesWorkersThatWereMerelyWaitingTheirTurnNotStuckForever() throws Exception {
        int itemCount = 40;
        Set<Integer> handedOut = ConcurrentHashMap.newKeySet();
        Set<Integer> completedIndices = ConcurrentHashMap.newKeySet();

        IndexSource bounded = new IndexSource() {
            final AtomicInteger cursor = new AtomicInteger();
            public int next() {
                int v = cursor.getAndIncrement();
                if (v >= itemCount) return -1;
                handedOut.add(v);
                return v;
            }
        };
        Workload workload = new Workload() {
            public Outcome execute(int index) {
                completedIndices.add(index);
                return new Outcome(1_000_000L, 1.0, 1, 0, false);
            }
            public double estimatedCapacityUnits(int index) { return 1.0; }
            public double maxCapacityUnits() { return 1.0; }
            public byte phaseId() { return 0; }
        };

        // A real, continuously-refilling rate: 40 CU/s against 1.0 CU/request, with an
        // 8-thread pool that could otherwise far outrun it -- so several workers are still
        // queued on acquire() at the moment the 40th (last) index is dequeued and exhaustion
        // trips, but every one of them gets its tokens within a couple of seconds.
        TokenBucket bucket = new TokenBucket(40.0, 0.5, 1.0);
        bucket.start();
        RecordWriter writer = new RecordWriter(tmp.resolve("happy-path.bin.gz"), 64, 256);
        writer.start();
        WorkerPool pool = new WorkerPool(workload, bucket,
            new CapacityMeter(10_000_000_000L, 100),
            new PhaseStats("t", 1_000_000L), writer,
            bounded, 8, 8, System.nanoTime());

        pool.start();
        int abandoned;
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> { while (!pool.exhausted()) Thread.sleep(1); });
            abandoned = pool.drain(Duration.ofSeconds(10));
        } finally {
            pool.stop();
            writer.close();
            bucket.close();
        }

        System.out.printf("HAPPY-PATH handedOut=%d completed=%d abandoned=%d%n",
            handedOut.size(), completedIndices.size(), abandoned);

        assertThat(handedOut).hasSize(itemCount);
        assertThat(abandoned).as("nothing should need to be abandoned when tokens keep arriving")
            .isZero();
        assertThat(completedIndices)
            .as("every handed-out index must have completed -- no work lost")
            .isEqualTo(handedOut);
    }

    /**
     * drain() itself must never hang past its timeout, no matter how stuck the workers are --
     * it is the direct replacement for an unbounded wait, and reports exactly how many
     * workers it gave up on so the caller can log it loudly instead of losing the information.
     */
    @Test
    void drainNeverWaitsPastItsTimeoutAndReportsHowManyAreStillRunning() throws Exception {
        Rig rig = buildRig(THREADS);
        rig.pool().start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> { while (!rig.pool().exhausted()) Thread.sleep(1); });

            int abandoned = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> rig.pool().drain(Duration.ofMillis(100)));

            // One worker (the one that observed exhaustion) already finished and exited;
            // the rest are permanently stuck on a bucket that never refills in this test.
            assertThat(abandoned).isEqualTo(THREADS - 1);
            assertThat(rig.pool().liveThreadCount()).isEqualTo(THREADS - 1);
        } finally {
            rig.pool().stop();
            rig.writer().close();
            rig.bucket().close();
        }
    }
}
