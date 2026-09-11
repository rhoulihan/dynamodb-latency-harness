package com.rickh.ddblat.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RecordWriterTest {

    @TempDir
    Path tmp;

    private static List<LatencyRecord.Decoded> readAll(Path file) throws IOException {
        byte[] raw;
        try (GZIPInputStream in = new GZIPInputStream(
                 new BufferedInputStream(new FileInputStream(file.toFile())))) {
            raw = in.readAllBytes();
        }
        assertThat(raw.length % LatencyRecord.BYTES)
            .as("file length %d is not a whole number of records", raw.length)
            .isZero();

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        List<LatencyRecord.Decoded> decoded = new ArrayList<>(raw.length / LatencyRecord.BYTES);
        for (int off = 0; off < raw.length; off += LatencyRecord.BYTES) {
            decoded.add(LatencyRecord.decode(buf, off));
        }
        return decoded;
    }

    @Test
    void writesEveryRecordToTheGzippedFileInSubmissionOrder() throws Exception {
        Path file = tmp.resolve("requests.bin.gz");
        RecordWriter writer = new RecordWriter(file, 4, 100);
        writer.start();

        RecordBuffer buf = writer.acquire();
        for (int i = 0; i < 1000; i++) {
            if (!buf.tryAppend(i, i * 2L, i * 5, (short) 1, (byte) 3, 1, 0)) {
                writer.submit(buf);
                buf = writer.acquire();
                assertThat(buf.tryAppend(i, i * 2L, i * 5, (short) 1, (byte) 3, 1, 0)).isTrue();
            }
        }
        writer.submit(buf);
        writer.close();

        List<LatencyRecord.Decoded> all = readAll(file);
        assertThat(all).hasSize(1000);
        for (int i = 0; i < 1000; i++) {
            LatencyRecord.Decoded d = all.get(i);
            assertThat(d.startNanos()).as("record %d", i).isEqualTo(i);
            assertThat(d.latencyNanos()).as("record %d", i).isEqualTo(i * 2L);
            assertThat(d.consumedCuTimes100()).as("record %d", i).isEqualTo(i * 5);
            assertThat(d.threadId()).as("record %d", i).isEqualTo((short) 1);
            assertThat(d.phaseId()).as("record %d", i).isEqualTo((byte) 3);
            assertThat(d.attempts()).as("record %d", i).isEqualTo(1);
            assertThat(d.statusClass()).as("record %d", i).isZero();
        }
        assertThat(writer.recordsWritten()).isEqualTo(1000L);
    }

    @Test
    void recordsWrittenMatchesWhatWasSubmitted() throws Exception {
        Path file = tmp.resolve("requests.bin.gz");
        RecordWriter writer = new RecordWriter(file, 2, 50);
        writer.start();

        for (int b = 0; b < 3; b++) {
            RecordBuffer buf = writer.acquire();
            for (int i = 0; i < 50; i++) {
                assertThat(buf.tryAppend(i, i, i, (short) 0, (byte) 0, 1, 0)).isTrue();
            }
            writer.submit(buf);
        }
        writer.close();

        assertThat(writer.recordsWritten()).isEqualTo(150L);
        assertThat(readAll(file)).hasSize(150);
    }

    @Test
    void acquireReturnsAResetBuffer() throws Exception {
        Path file = tmp.resolve("requests.bin.gz");
        RecordWriter writer = new RecordWriter(file, 1, 10);
        writer.start();

        RecordBuffer first = writer.acquire();
        assertThat(first.count()).isZero();
        assertThat(first.isFull()).isFalse();
        for (int i = 0; i < 10; i++) {
            assertThat(first.tryAppend(i, i, i, (short) 0, (byte) 0, 1, 0)).isTrue();
        }
        assertThat(first.isFull()).isTrue();
        writer.submit(first);

        // Only one buffer exists, so this is the same instance coming back through the writer.
        RecordBuffer second = writer.acquire();
        assertThat(second.count()).isZero();
        assertThat(second.isFull()).isFalse();
        assertThat(second.capacityRecords()).isEqualTo(10);
        assertThat(second.readOnlyView().remaining()).isZero();

        writer.submit(second);
        writer.close();
        assertThat(writer.recordsWritten()).isEqualTo(10L);
    }

    @Test
    void buffersAreRecycledAcrossManyMoreCyclesThanExist() throws Exception {
        Path file = tmp.resolve("requests.bin.gz");
        int totalBuffers = 3;
        int recordsPerBuffer = 20;
        int cycles = 30;

        RecordWriter writer = new RecordWriter(file, totalBuffers, recordsPerBuffer);
        writer.start();
        for (int c = 0; c < cycles; c++) {
            RecordBuffer buf = writer.acquire();
            for (int i = 0; i < recordsPerBuffer; i++) {
                assertThat(buf.tryAppend(c * recordsPerBuffer + i, 1L, 0, (short) 0, (byte) 0, 1, 0))
                    .as("cycle %d append %d", c, i)
                    .isTrue();
            }
            writer.submit(buf);
        }
        writer.close();

        List<LatencyRecord.Decoded> all = readAll(file);
        assertThat(all).hasSize(cycles * recordsPerBuffer);
        for (int i = 0; i < all.size(); i++) {
            assertThat(all.get(i).startNanos()).as("record %d", i).isEqualTo(i);
        }
        assertThat(writer.recordsWritten()).isEqualTo((long) cycles * recordsPerBuffer);
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Path file = tmp.resolve("requests.bin.gz");
        RecordWriter writer = new RecordWriter(file, 2, 10);
        writer.start();

        RecordBuffer buf = writer.acquire();
        assertThat(buf.tryAppend(1L, 2L, 3, (short) 4, (byte) 5, 1, 0)).isTrue();
        writer.submit(buf);

        writer.close();
        assertThatCode(writer::close).doesNotThrowAnyException();
        assertThatCode(writer::close).doesNotThrowAnyException();

        assertThat(writer.recordsWritten()).isEqualTo(1L);
        assertThat(readAll(file)).hasSize(1);
    }

    @Test
    void fourConcurrentWorkersLandEveryRecord() throws Exception {
        Path file = tmp.resolve("requests.bin.gz");
        int threads = 4;
        int perThread = 500;
        int recordsPerBuffer = 64;

        RecordWriter writer = new RecordWriter(file, threads * 2, recordsPerBuffer);
        writer.start();

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final short id = (short) t;
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                    RecordBuffer buf = writer.acquire();
                    for (int i = 0; i < perThread; i++) {
                        if (!buf.tryAppend(i, 1L, 0, id, (byte) 1, 1, 0)) {
                            writer.submit(buf);
                            buf = writer.acquire();
                            buf.tryAppend(i, 1L, 0, id, (byte) 1, 1, 0);
                        }
                    }
                    writer.submit(buf);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }, "test-worker-" + t);
            worker.start();
        }

        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("workers finished").isTrue();
        writer.close();

        assertThat(failure.get()).isNull();
        assertThat(writer.recordsWritten()).isEqualTo((long) threads * perThread);

        List<LatencyRecord.Decoded> all = readAll(file);
        assertThat(all).hasSize(threads * perThread);

        int[] perThreadCount = new int[threads];
        for (LatencyRecord.Decoded d : all) {
            perThreadCount[d.threadId()]++;
        }
        assertThat(perThreadCount).containsOnly(perThread);
    }

    @Test
    void acquireThrowsInsteadOfHangingForeverWhenTheWriterThreadDies() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // No parent directory exists, so the FileOutputStream inside drainLoop throws
            // immediately and the writer thread dies before processing anything -- this forces
            // the failure without needing a production-code seam.
            Path badFile = tmp.resolve("no-such-directory").resolve("requests.bin.gz");
            RecordWriter writer = new RecordWriter(badFile, 1, 10);
            writer.start();

            // The single buffer is handed out immediately regardless of writer health.
            RecordBuffer buf = writer.acquire();
            assertThat(buf).isNotNull();

            // The free list is now empty and can never refill: the writer already died and
            // will never return a buffer. A plain take() would hang this test forever;
            // acquire() must throw a loud, diagnosable exception instead.
            assertThatThrownBy(writer::acquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("writer thread died");
        });
    }

    @Test
    void closeReportsAnAbandonedBufferInsteadOfHangingOrSilentlyDroppingIt() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            Path file = tmp.resolve("requests.bin.gz");
            // Package-private constructor shrinks close()'s drain-wait budget so this test
            // observes the timeout-then-proceed behavior without waiting out the real 30s
            // production budget.
            RecordWriter writer = new RecordWriter(file, 2, 10, Duration.ofMillis(300));
            writer.start();

            RecordBuffer buf = writer.acquire();
            assertThat(buf.tryAppend(1L, 2L, 3, (short) 4, (byte) 5, 1, 0)).isTrue();
            // Deliberately never submit(buf): simulates a worker that died, or was killed,
            // before flushing its final, partial buffer.

            writer.close();

            assertThat(writer.buffersAbandonedAtClose()).isEqualTo(1L);
            // The abandoned buffer's record never reached the writer, so the file is empty
            // but still a well-formed (finalized) gzip stream.
            assertThat(readAll(file)).isEmpty();
        });
    }
}
