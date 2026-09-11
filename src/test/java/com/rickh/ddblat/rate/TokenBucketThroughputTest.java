package com.rickh.ddblat.rate;

import com.rickh.ddblat.Main;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.DoubleAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces, and then guards against, a real AWS smoke run: the read phase's bucket delivered
 * 845 RCU/min (~14 RCU/s) against a 100 RCU/s floor target -- about 14% of nominal -- with eight
 * workers parked in {@link TokenBucket#acquire}. Because {@link RampController} only advances
 * once achieved throughput reaches 95% of target, the ramp could never advance: not a hard
 * deadlock (that was a separate, already-fixed bug -- see the class javadoc on {@link
 * TokenBucket}), but a soft one that presents as an inexplicably slow run.
 *
 * Each case here starts a real bucket, hammers it from N threads with a tight acquire loop for a
 * few seconds, and measures the units actually granted -- exactly the methodology the live
 * incident called for ("write the measurement first, then make it pass") rather than reasoning
 * about capacity math analytically, which had already produced two wrong fixes in a row. The
 * first row is the exact failing configuration from the live run; the 4,000 row is production's
 * ramp floor; the 36,000 rows are production's 90%-of-ceiling targets.
 *
 * What the measurements actually showed, run repeatedly against this exact test on this hardware,
 * across 1-512 threads and request latencies from zero up to 2 seconds: a bare, maximally
 * contended acquire loop against {@link TokenBucket} sustains 95-100%+ of nominal throughput at
 * EVERY capacity floor tried, including a floor of exactly one request's cost (factor 1, the
 * prior/buggy sizing) -- the lock-free CAS-and-adaptive-park design in {@code acquire} already
 * lets whichever thread is ready grab tokens the instant they exist, so contention alone (however
 * many threads, however small the bucket) does not reproduce the field's 14% collapse. Real
 * per-request latency only degrades throughput once it grows large enough (several hundred ms to
 * seconds) that the WORKER POOL's own concurrency -- threads / latency -- falls below the target
 * request rate, and that degradation is uniform across every capacity floor tested: it is a
 * client-concurrency problem (already {@link RampController}'s job, via its at-cap thread-growth
 * signal), not a bucket-capacity one.
 *
 * So {@link Main#CAPACITY_FLOOR_REQUESTS} was NOT raised because these measurements demanded it
 * to pass -- factor 1 already clears every row below comfortably. It was raised as a cheap,
 * low-regret safety margin against real-infrastructure hiccups (a delayed refiller tick, a GC
 * pause, EC2 CPU-credit throttling) that this synthetic, single-process JVM test cannot
 * reproduce, and which leave a one-request bucket with no banked slack at all. See {@link
 * Main#CAPACITY_FLOOR_REQUESTS}'s javadoc for the full reasoning and the resulting burst cost.
 */
class TokenBucketThroughputTest {

    private static final double ACHIEVED_FRACTION = 0.90;
    private static final double BURST_SECONDS = 0.1;
    private static final double RUN_SECONDS = 3.0;

    /**
     * Starts a fresh bucket sized the way {@link Main#runPhase} sizes one, runs {@code threads}
     * threads calling {@code acquire(requestCost)} back-to-back for {@code RUN_SECONDS}, and
     * returns the total units actually granted divided by wall-clock elapsed time.
     */
    private static double measureAchievedUnitsPerSecond(double unitsPerSecond, double requestCost,
            int threads) throws InterruptedException {
        double minCapacityUnits = requestCost * Main.CAPACITY_FLOOR_REQUESTS;
        try (TokenBucket bucket = new TokenBucket(unitsPerSecond, BURST_SECONDS, minCapacityUnits)) {
            bucket.start();
            DoubleAdder granted = new DoubleAdder();
            CountDownLatch go = new CountDownLatch(1);
            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> {
                    try {
                        go.await();
                        long deadline = System.nanoTime() + (long) (RUN_SECONDS * 1_000_000_000L);
                        while (System.nanoTime() < deadline) {
                            bucket.acquire(requestCost);
                            granted.add(requestCost);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "throughput-probe-" + i);
                workers[i].setDaemon(true);
            }
            long t0 = System.nanoTime();
            for (Thread w : workers) w.start();
            go.countDown();
            for (Thread w : workers) w.join();
            double elapsedSeconds = (System.nanoTime() - t0) / 1_000_000_000.0;
            return granted.sum() / elapsedSeconds;
        }
    }

    @ParameterizedTest(name = "{index}: {0} CU/s, {1} CU/request, {2} threads")
    @CsvSource({
        // unitsPerSecond, requestCost, threads
        "100,    13,   8",     // exact failing configuration from the live run
        "100,    52,   8",     // smoke-scale PUT: bigger request, same rate/thread count
        "1000,   52,   8",
        "4000,   50,  32",     // production's LOAD ramp floor
        "36000,  50,  32",     // production target: 90% of a 40,000 WCU ceiling
        "36000,  13,  32",     // production target: strongly-consistent reads
        "36000,  6.5, 32",     // production target: eventually-consistent reads
    })
    void sustainsAtLeastNinetyPercentOfNominalThroughputUnderContention(
            double unitsPerSecond, double requestCost, int threads) throws Exception {
        double achieved = measureAchievedUnitsPerSecond(unitsPerSecond, requestCost, threads);

        assertThat(achieved)
            .as("%.1f CU/s achieved against a %.1f CU/s nominal target (%.1f CU/request, %d threads)",
                achieved, unitsPerSecond, requestCost, threads)
            .isGreaterThanOrEqualTo(ACHIEVED_FRACTION * unitsPerSecond);
    }
}
