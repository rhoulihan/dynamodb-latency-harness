package com.rickh.ddblat.rate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class TokenBucketTest {

    private static final double BURST_SECONDS = 0.1;   // production burst cap: 100 ms

    /** Drains greedily in 1-unit grants and returns how many units came out. */
    private static double drain(TokenBucket bucket) {
        double drained = 0;
        while (bucket.tryAcquire(1.0)) drained += 1.0;
        return drained;
    }

    @Test
    void rateIsAccurateWithinTwentyPercentOverTwoSeconds() throws Exception {
        try (TokenBucket bucket = new TokenBucket(1_000.0, BURST_SECONDS)) {
            drain(bucket);                      // discard the initial burst so only refill is measured
            bucket.start();
            long t0 = System.nanoTime();
            int granted = 0;
            while (System.nanoTime() - t0 < 2_000_000_000L) {
                if (bucket.tryAcquire(1.0)) granted++;
                else Thread.onSpinWait();
            }
            assertThat(granted).isBetween(1_600, 2_400);        // 2000 +/- 20%
        }
    }

    @Test
    void burstNeverExceedsTheConfiguredCap() throws Exception {
        try (TokenBucket bucket = new TokenBucket(100.0, BURST_SECONDS)) {   // cap = 10 units
            bucket.start();
            Thread.sleep(500);                  // 50 units would accrue if the cap did not hold
            assertThat(drain(bucket)).isBetween(8.0, 12.0);     // 10 +/- 20%
        }
    }

    @Test
    void tryAcquireFailsOnAnEmptyBucket() {
        try (TokenBucket bucket = new TokenBucket(1_000.0, BURST_SECONDS)) {  // cap = 100 units
            assertThat(bucket.tryAcquire(101.0)).isFalse();     // larger than the whole burst
            assertThat(drain(bucket)).isEqualTo(100.0);
            assertThat(bucket.tryAcquire(1.0)).isFalse();
            assertThat(bucket.tryAcquire(0.5)).isFalse();
        }
    }

    @Test
    void setRateChangesBothTheFillRateAndTheBurstCap() throws Exception {
        try (TokenBucket bucket = new TokenBucket(100.0, BURST_SECONDS)) {    // cap = 10 units
            assertThat(bucket.rate()).isEqualTo(100.0);
            bucket.setRate(1_000.0);                                          // cap = 100 units
            assertThat(bucket.rate()).isEqualTo(1_000.0);
            bucket.start();
            Thread.sleep(300);                  // 300 units at the new rate, clamped to the new cap
            assertThat(drain(bucket)).isBetween(80.0, 120.0);
        }
    }

    @Test
    void concurrentAcquireNeverIssuesMoreThanTheBucketProduced() throws Exception {
        final double rate = 5_000.0;
        try (TokenBucket bucket = new TokenBucket(rate, BURST_SECONDS)) {     // cap = 500 units
            LongAdder granted = new LongAdder();
            CountDownLatch go = new CountDownLatch(1);
            Thread[] workers = new Thread[8];
            long t0 = System.nanoTime();        // taken before start(): the bucket is already full
            bucket.start();
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    long end = System.nanoTime() + 1_000_000_000L;
                    while (System.nanoTime() < end) {
                        if (bucket.tryAcquire(1.0)) granted.increment();
                        else Thread.onSpinWait();
                    }
                });
                workers[i].start();
            }
            go.countDown();
            for (Thread w : workers) w.join();
            long elapsedNanos = System.nanoTime() - t0;

            // Hard bound: the bucket can never have issued more than one burst plus rate * elapsed.
            double issued = rate * BURST_SECONDS + rate * (elapsedNanos / 1e9);
            assertThat((double) granted.sum()).isLessThanOrEqualTo(issued);
            assertThat((double) granted.sum()).isGreaterThan(rate * 0.5);     // it really did run
        }
    }

    @Test
    void isAtCapIsTrueWhenIdleAndFalseImmediatelyAfterADrain() throws Exception {
        try (TokenBucket bucket = new TokenBucket(1_000.0, BURST_SECONDS)) {  // cap = 100 units
            bucket.start();
            Thread.sleep(200);
            assertThat(bucket.isAtCap()).isTrue();
            assertThat(bucket.tryAcquire(100.0)).isTrue();      // takes the entire burst
            assertThat(bucket.isAtCap()).isFalse();             // 100 ms of refill to get back
        }
    }

    @Test
    void continuouslyAtCapNanosArmsOnFirstPollAndResetsWhenTokensAreConsumed() {
        try (TokenBucket bucket = new TokenBucket(1_000.0, BURST_SECONDS)) {
            long t = 5_000_000_000L;                            // never started: no refill interference
            assertThat(bucket.isAtCap()).isTrue();
            assertThat(bucket.continuouslyAtCapNanos(t)).isZero();               // first poll arms
            assertThat(bucket.continuouslyAtCapNanos(t + 2_000_000_000L)).isEqualTo(2_000_000_000L);
            assertThat(bucket.tryAcquire(1.0)).isTrue();
            assertThat(bucket.continuouslyAtCapNanos(t + 3_000_000_000L)).isZero();
            assertThat(bucket.continuouslyAtCapNanos(t + 4_000_000_000L)).isZero();
        }
    }

    /**
     * Reproduces the production incident directly: loadWcu=1000 -> ramp floor 100 CU/s ->
     * TokenBucket(100, 0.1) -> cap = 10 CU, but a single ~50 KiB PutItem costs ~50 CU. Without
     * the fix, acquire(50) parks forever (this is exactly what 64 threads did on EC2, per a
     * SIGQUIT thread dump, for 11+ minutes). assertTimeoutPreemptively makes a regression fail
     * fast in this suite rather than hanging it.
     */
    @Test
    void acquireOfMoreThanTheBucketsCapacityFailsFastInsteadOfParkingForever() {
        try (TokenBucket bucket = new TokenBucket(100.0, BURST_SECONDS)) {   // cap = 10 CU
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> bucket.acquire(50.0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("can never be satisfied")
                    .hasMessageContaining("10")
                    .hasMessageContaining("50"));
        }
    }

    @Test
    void minCapacityUnitsFloorsCapacitySoAMaxSizeRequestFitsEvenAtALowRate() throws Exception {
        // Same rate/burst as above (cap would be 10 CU), but floored at 52 CU -- the real
        // fix Main applies: pass the largest single-request cost from the size model.
        try (TokenBucket bucket = new TokenBucket(100.0, BURST_SECONDS, 52.0)) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> bucket.acquire(50.0));
        }
    }

    @Test
    void setRateCannotShrinkCapacityBelowTheFloorWhileARequestIsWaiting() throws Exception {
        // Starts at a rate high enough that 50 CU fits comfortably (cap = 1000), then the
        // ramp throttles down to a rate that would -- without the floor -- shrink the cap to
        // 10, below the 50 CU request. With a 52 CU floor the cap holds at 52 and the request
        // that was already satisfiable stays satisfiable, instead of silently hanging on the
        // ramp's throttle-backoff path.
        try (TokenBucket bucket = new TokenBucket(10_000.0, BURST_SECONDS, 52.0)) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> bucket.acquire(50.0));
            bucket.setRate(100.0);   // would floor cap at 10 without minCapacityUnits; floored at 52
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> bucket.acquire(50.0));
        }
    }
}
