package com.rickh.ddblat.worker;

import com.rickh.ddblat.metrics.*;
import com.rickh.ddblat.rate.TokenBucket;
import com.rickh.ddblat.record.RecordWriter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class WorkerPoolTest {

    @TempDir Path tmp;

    /** Deterministic stand-in: no AWS, fixed latency, fixed cost. */
    private static final class FakeWorkload implements Workload {
        final AtomicInteger calls = new AtomicInteger();
        private volatile byte phase = 0;
        public Outcome execute(int index) {
            calls.incrementAndGet();
            return new Outcome(1_000_000L, 2.0, 1, 0, false);
        }
        public double estimatedCapacityUnits(int index) { return 2.0; }
        public double maxCapacityUnits() { return 2.0; }
        public byte phaseId() { return phase; }
    }

    private static IndexSource bounded(int n) {
        AtomicInteger i = new AtomicInteger();
        return () -> { int v = i.getAndIncrement(); return v < n ? v : -1; };
    }

    @Test
    void runsEveryIndexExactlyOnceThenStops() throws Exception {
        FakeWorkload wl = new FakeWorkload();
        TokenBucket bucket = new TokenBucket(100_000, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(10_000_000_000L, 100);
        PhaseStats stats = new PhaseStats("t", 1_000_000L);
        RecordWriter writer = new RecordWriter(tmp.resolve("r.bin.gz"), 64, 256);
        writer.start();

        WorkerPool pool = new WorkerPool(wl, bucket, meter, stats, writer,
            bounded(500), 4, 16, System.nanoTime());
        pool.start();
        // Wait for every request to be RECORDED, not just for the index source to run dry:
        // exhausted() flips true the instant any one thread's next() call returns -1, which
        // can happen while other threads still have their own in-flight (already-dequeued)
        // request outstanding. Now that stop() interrupts workers (see its javadoc), calling
        // it the instant exhausted() is observed could abort one of those in-flight requests
        // before it is recorded -- exactly the crash-like event LoadPhase's checkpoint margin
        // exists to tolerate in production, but not what this test is exercising. Waiting for
        // completed() to reach the full count closes that race deterministically.
        while (pool.completed() < 500) Thread.sleep(20);
        pool.stop();
        writer.close();
        bucket.close();

        assertThat(wl.calls.get()).isEqualTo(500);
        assertThat(pool.completed()).isEqualTo(500L);
        assertThat(writer.recordsWritten()).isEqualTo(500L);
    }

    @Test
    void addThreadsGrowsThePoolAndRespectsTheCap() throws Exception {
        FakeWorkload wl = new FakeWorkload();
        TokenBucket bucket = new TokenBucket(100_000, 0.1);
        bucket.start();
        RecordWriter writer = new RecordWriter(tmp.resolve("r2.bin.gz"), 64, 256);
        writer.start();

        WorkerPool pool = new WorkerPool(wl, bucket,
            new CapacityMeter(10_000_000_000L, 100),
            new PhaseStats("t", 1_000_000L), writer,
            bounded(Integer.MAX_VALUE), 4, 16, System.nanoTime());
        pool.start();
        assertThat(pool.threadCount()).isEqualTo(4);
        pool.addThreads(8);
        assertThat(pool.threadCount()).isEqualTo(12);
        pool.addThreads(100);
        assertThat(pool.threadCount()).isEqualTo(16);   // capped
        pool.stop();
        writer.close();
        bucket.close();
    }

    @Test
    void statsAndPhaseIdCanBeSwappedWhenAMeasurementWindowOpens() throws Exception {
        FakeWorkload wl = new FakeWorkload();
        // Paced so the 2000 requests take about a second. At an unthrottled rate the whole
        // workload finishes during the sleep below and the window stats come back empty,
        // which makes the test assert nothing.
        TokenBucket bucket = new TokenBucket(4_000, 0.1);
        bucket.start();
        RecordWriter writer = new RecordWriter(tmp.resolve("r3.bin.gz"), 64, 256);
        writer.start();
        PhaseStats rampStats = new PhaseStats("ramp", 1_000_000L);
        PhaseStats windowStats = new PhaseStats("window", 1_000_000L);

        WorkerPool pool = new WorkerPool(wl, bucket,
            new CapacityMeter(10_000_000_000L, 100), rampStats, writer,
            bounded(2000), 4, 16, System.nanoTime());
        pool.setPhaseId((byte) 0);
        pool.start();
        Thread.sleep(300);
        pool.setStats(windowStats);
        pool.setPhaseId((byte) 1);
        // See the comment in runsEveryIndexExactlyOnceThenStops: wait for full completion,
        // not mere exhaustion, so stop()'s interrupt cannot abort a still-in-flight request.
        while (pool.completed() < 2000) Thread.sleep(20);
        pool.stop();
        writer.close();
        bucket.close();

        assertThat(rampStats.raw().count()).isPositive();
        assertThat(windowStats.raw().count()).isPositive();
        assertThat(rampStats.raw().count() + windowStats.raw().count()).isEqualTo(2000L);
    }

    @Test
    void throttledOutcomesAreCountedAndDoNotStopThePool() throws Exception {
        Workload throttling = new Workload() {
            final AtomicInteger n = new AtomicInteger();
            public Outcome execute(int i) {
                boolean t = n.incrementAndGet() % 10 == 0;
                return new Outcome(500_000L, t ? 0 : 2.0, 1, t ? 1 : 0, t);
            }
            public double estimatedCapacityUnits(int i) { return 2.0; }
            public double maxCapacityUnits() { return 2.0; }
            public byte phaseId() { return 0; }
        };
        TokenBucket bucket = new TokenBucket(100_000, 0.1);
        bucket.start();
        PhaseStats stats = new PhaseStats("t", 1_000_000L);
        RecordWriter writer = new RecordWriter(tmp.resolve("r4.bin.gz"), 64, 256);
        writer.start();

        WorkerPool pool = new WorkerPool(throttling, bucket,
            new CapacityMeter(10_000_000_000L, 100), stats, writer,
            bounded(100), 4, 16, System.nanoTime());
        pool.start();
        // See the comment in runsEveryIndexExactlyOnceThenStops: wait for full completion,
        // not mere exhaustion, so stop()'s interrupt cannot abort a still-in-flight request.
        while (pool.completed() < 100) Thread.sleep(20);
        pool.stop();
        writer.close();
        bucket.close();

        assertThat(stats.throttles()).isEqualTo(10L);
        assertThat(pool.completed()).isEqualTo(100L);
    }

    @Test
    void hitAndMissOutcomesAreCountedOnlyForSuccessfulResponses() throws Exception {
        Workload mixed = new Workload() {
            final AtomicInteger n = new AtomicInteger();
            public Outcome execute(int i) {
                int k = n.incrementAndGet();
                if (k % 10 == 0) {
                    // throttled: neither a hit nor a miss, must not be counted as either.
                    return new Outcome(500_000L, 0, 1, 1, true);
                }
                boolean found = k % 3 != 0;   // a mix of hits and misses among successes
                return new Outcome(500_000L, 2.0, 1, 0, false, found);
            }
            public double estimatedCapacityUnits(int i) { return 2.0; }
            public double maxCapacityUnits() { return 2.0; }
            public byte phaseId() { return 0; }
        };
        TokenBucket bucket = new TokenBucket(100_000, 0.1);
        bucket.start();
        PhaseStats stats = new PhaseStats("t", 1_000_000L);
        RecordWriter writer = new RecordWriter(tmp.resolve("r9.bin.gz"), 64, 256);
        writer.start();

        int n = 300;
        WorkerPool pool = new WorkerPool(mixed, bucket,
            new CapacityMeter(10_000_000_000L, 100), stats, writer,
            bounded(n), 4, 16, System.nanoTime());
        pool.start();
        while (pool.completed() < n) Thread.sleep(20);
        pool.stop();
        writer.close();
        bucket.close();

        // Of 300 calls: 30 are throttled (k % 10 == 0, counted as neither), leaving 270
        // successes. Among successes, k % 3 == 0 is a miss -- but k % 10 == 0 implies
        // k % 5 == 0, not k % 3 == 0, so no overlap between the two conditions to account
        // for: exactly 100 of the 300 satisfy k % 3 == 0 (misses among ALL k), all 300/10=30
        // of which... simplest to just assert the invariant rather than hand-derive the
        // split: hits + misses must equal completed - throttled, and misses must be positive.
        assertThat(stats.throttles()).isEqualTo(30L);
        assertThat(stats.hits() + stats.misses()).isEqualTo(270L);
        assertThat(stats.misses()).isPositive();
        assertThat(stats.hits()).isPositive();
    }

    @Test
    void stopInterruptsWorkersParkedInTokenBucketAcquireRatherThanLeakingThem() throws Exception {
        FakeWorkload wl = new FakeWorkload();     // costs 2.0 CU per call
        // minCapacityUnits=2.0 keeps the request genuinely satisfiable (cap == cost, so the
        // fail-fast check in TokenBucket.acquire does not fire) while the rate is so low
        // (floored at 1 micro-unit/sec) that, once the exactly-2.0-CU initial burst is spent,
        // no further credit can accrue within this test's lifetime -- every worker is
        // certainly parked in TokenBucket.acquire() waiting for tokens that, from this
        // test's point of view, will never come. This mirrors production: Main closes the
        // bucket right after PhaseRunner.run() returns, so a worker parked here at that
        // moment would otherwise spin-wake forever.
        TokenBucket bucket = new TokenBucket(0.0001, 0.001, 2.0);
        bucket.start();
        RecordWriter writer = new RecordWriter(tmp.resolve("r5.bin.gz"), 64, 256);
        writer.start();

        WorkerPool pool = new WorkerPool(wl, bucket,
            new CapacityMeter(10_000_000_000L, 100),
            new PhaseStats("t", 1_000_000L), writer,
            bounded(Integer.MAX_VALUE), 4, 16, System.nanoTime());
        pool.start();
        // Give the scheduler a chance to actually run every worker into acquire() and park.
        Thread.sleep(200);
        assertThat(pool.liveThreadCount()).isEqualTo(4);

        // Without WorkerPool.stop() interrupting the workers, this either times out (each
        // of the 4 threads' 5s join() elapses fully since nothing ever wakes them) or
        // returns with threads still alive -- either way the assertion below fails. With
        // the interrupt, every worker wakes immediately, TokenBucket.acquire() throws
        // InterruptedException, runWorker's catch converts it into a clean return (still
        // running its finally, which submits the partial buffer), and stop() returns in
        // well under a second.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            pool.stop();
            assertThat(pool.liveThreadCount())
                .as("no worker thread should remain alive after stop()")
                .isZero();
        });

        writer.close();
        bucket.close();
    }

    @Test
    void writerBackpressureCannotInflateRecordedLatency() throws Exception {
        final long fixedLatencyNanos = 777_777L;
        Workload fixedLatency = new Workload() {
            public Outcome execute(int index) {
                return new Outcome(fixedLatencyNanos, 1.0, 1, 0, false);
            }
            public double estimatedCapacityUnits(int index) { return 1.0; }
            public double maxCapacityUnits() { return 1.0; }
            public byte phaseId() { return 0; }
        };

        // Baseline: how HdrHistogram quantizes this exact latency with zero contention. If
        // writer backpressure ever leaked into a recorded latency, the pool-driven run
        // below would diverge from this, whatever the quantization happens to be.
        PhaseStats baseline = new PhaseStats("baseline", 1_000_000L);
        baseline.record(fixedLatencyNanos);

        TokenBucket bucket = new TokenBucket(1_000_000, 1.0);   // effectively unpaced
        bucket.start();
        // totalBuffers=1, recordsPerBuffer=1: exactly one buffer exists in the whole pool
        // and it holds exactly one record before it is full. Every request after the first
        // must therefore wait for the writer thread to actually drain and return that one
        // buffer before acquire() can succeed again -- genuine blocking on every iteration,
        // not a simulated stall.
        RecordWriter writer = new RecordWriter(tmp.resolve("r6.bin.gz"), 1, 1);
        writer.start();
        PhaseStats stats = new PhaseStats("t", 1_000_000L);

        int n = 100;
        WorkerPool pool = new WorkerPool(fixedLatency, bucket,
            new CapacityMeter(10_000_000_000L, 100), stats, writer,
            bounded(n), 4, 8, System.nanoTime());
        pool.start();
        // See the comment in runsEveryIndexExactlyOnceThenStops: wait for full completion,
        // not mere exhaustion, so stop()'s interrupt cannot abort a still-in-flight request.
        while (pool.completed() < n) Thread.sleep(20);
        pool.stop();
        writer.close();
        bucket.close();

        assertThat(stats.raw().count()).isEqualTo((long) n);
        assertThat(stats.raw().max()).isEqualTo(baseline.raw().max());
        assertThat(stats.raw().p50()).isEqualTo(baseline.raw().p50());
        assertThat(stats.raw().mean()).isEqualTo(baseline.raw().mean());
    }

    @Test
    void stopCannotDoubleSubmitABufferWhenInterruptedWhileAcquiringItsReplacement() throws Exception {
        // Fast, synchronous workload with effectively unlimited work, so buffer-swap
        // pressure against the tiny writer pool below stays high right up until stop().
        Workload fast = new Workload() {
            public Outcome execute(int index) { return new Outcome(1_000L, 1.0, 1, 0, false); }
            public double estimatedCapacityUnits(int index) { return 1.0; }
            public double maxCapacityUnits() { return 1.0; }
            public byte phaseId() { return 0; }
        };
        TokenBucket bucket = new TokenBucket(1_000_000, 1.0);   // effectively unpaced
        bucket.start();
        // totalBuffers=1, recordsPerBuffer=1: after the very first swap, EVERY subsequent
        // swap for EVERY thread goes through the "second acquire()" inside the
        // tryAppend-fail branch -- the exact site of the ownership hazard -- and with only
        // one buffer shared across 6 threads, most of them are parked there (blocked in
        // acquire(), waiting for the writer to drain and return the sole buffer) at any
        // given instant, including the instant stop() interrupts them.
        RecordWriter writer = new RecordWriter(tmp.resolve("r7.bin.gz"), 1, 1);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        IndexSource unbounded = () -> i.getAndIncrement();   // never exhausts in this test

        WorkerPool pool = new WorkerPool(fast, bucket,
            new CapacityMeter(10_000_000_000L, 100),
            new PhaseStats("t", 1_000_000L), writer,
            unbounded, 6, 6, System.nanoTime());

        // RecordWriter.submit() throws IllegalStateException on a double-submit (see its
        // javadoc: a buffer submitted while not checked out). That throw happens on the
        // WORKER thread, inside runWorker's finally, so without the ownership fix it
        // surfaces as an uncaught exception rather than a normal assertion failure --
        // capture it via the default handler rather than missing it.
        List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<>());
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(e));
        try {
            pool.start();
            Thread.sleep(250);   // let buffer-swap contention build up under the 1-buffer pool
            assertTimeoutPreemptively(Duration.ofSeconds(5), pool::stop);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        writer.close();
        bucket.close();

        assertThat(uncaught)
            .as("no worker should submit a RecordBuffer it no longer owns: %s", uncaught)
            .isEmpty();
    }

    @Test
    void aRequestLargerThanTheBucketCapacitySurfacesAnErrorInsteadOfHangingThePool() throws Exception {
        // estimatedCapacityUnits (500) is far larger than the bucket's cap (10 units, no
        // floor): this is the exact shape of the production bug -- a smoke-scale ceiling
        // producing a bucket smaller than one request -- reproduced at the WorkerPool level
        // rather than TokenBucket's own unit tests.
        Workload oversized = new Workload() {
            public Outcome execute(int i) { return new Outcome(1_000L, 1.0, 1, 0, false); }
            public double estimatedCapacityUnits(int i) { return 500.0; }
            public double maxCapacityUnits() { return 500.0; }
            public byte phaseId() { return 0; }
        };
        TokenBucket bucket = new TokenBucket(100.0, 0.1);   // cap = 10 units, no floor applied
        bucket.start();
        RecordWriter writer = new RecordWriter(tmp.resolve("r8.bin.gz"), 64, 256);
        writer.start();

        // TokenBucket.acquire's IllegalStateException is unchecked, so runWorker's
        // catch(InterruptedException) does not swallow it -- it propagates out of the
        // worker thread uncaught. Capture it the same way as the double-submit test above.
        List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<>());
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(e));

        WorkerPool pool = new WorkerPool(oversized, bucket,
            new CapacityMeter(10_000_000_000L, 100),
            new PhaseStats("t", 1_000_000L), writer,
            bounded(Integer.MAX_VALUE), 4, 16, System.nanoTime());
        try {
            pool.start();
            // Without the fix every worker parks in acquire() forever and this times out;
            // with the fix every worker throws almost immediately.
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (uncaught.isEmpty()) Thread.sleep(20);
            });
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        pool.stop();
        writer.close();
        bucket.close();

        assertThat(uncaught)
            .as("an unsatisfiable request must surface loudly, never hang the pool: %s", uncaught)
            .isNotEmpty()
            .allSatisfy(t -> assertThat(t).isInstanceOf(IllegalStateException.class));
    }
}
