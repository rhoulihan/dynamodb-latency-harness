package com.rickh.ddblat.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PhaseStatsTest {

    private static final long MS = 1_000_000L;      // nanos in a millisecond

    /** Uniform 1..1000 ms. The expected interval is wide enough that no CO correction fires. */
    private static PhaseStats uniformMillis() {
        PhaseStats stats = new PhaseStats("R-A", 1_000 * MS);
        for (int i = 1; i <= 1_000; i++) stats.record(i * MS);
        return stats;
    }

    @Test
    void aKnownUniformDistributionYieldsTheExpectedPercentiles() {
        PhaseStats.Percentiles p = uniformMillis().raw();
        assertThat(p.p50()).isCloseTo(500_000.0, withinPercentage(1.0));     // microseconds
        assertThat(p.p90()).isCloseTo(900_000.0, withinPercentage(1.0));
        assertThat(p.p99()).isCloseTo(990_000.0, withinPercentage(1.0));
        assertThat(p.p999()).isCloseTo(999_000.0, withinPercentage(1.0));
        assertThat(p.p9999()).isCloseTo(1_000_000.0, withinPercentage(1.0));
        assertThat(p.mean()).isCloseTo(500_500.0, withinPercentage(1.0));    // (1+..+1000)/1000 ms
        assertThat(p.stddev()).isGreaterThan(0.0);
    }

    @Test
    void countIsExactAndMaxIsTheLargestSampleWithinHistogramPrecision() {
        PhaseStats.Percentiles p = uniformMillis().raw();
        assertThat(p.count()).isEqualTo(1_000L);
        assertThat(p.max()).isCloseTo(1_000_000L, withinPercentage(0.5));    // 1000 ms in micros
    }

    @Test
    void correctedIsMateriallyWorseThanRawAtP99WhenTheServiceStalls() {
        PhaseStats stats = new PhaseStats("R-B", 1 * MS);      // open-loop pacing at 1 ms
        for (int i = 0; i < 1_000; i++) stats.record(1 * MS);
        stats.record(10_000 * MS);                             // one 10 s stall

        PhaseStats.Percentiles raw = stats.raw();
        PhaseStats.Percentiles corrected = stats.corrected();

        assertThat(raw.count()).isEqualTo(1_001L);
        assertThat(raw.p99()).isLessThan(2_000.0);                       // ~1 ms, the stall vanishes
        assertThat(corrected.count()).isEqualTo(11_000L);                // 9,999 samples back-filled
        assertThat(corrected.p99()).isGreaterThan(9_000_000.0);          // ~9.9 s
        assertThat(corrected.p99()).isGreaterThan(raw.p99() * 100);
    }

    @Test
    void concurrentRecordingLosesNoSamples() throws Exception {
        // Plain HdrHistogram.Histogram is not thread-safe. With 16 workers recording into a
        // single PhaseStats it drops counts silently -- the failure mode is a wrong P99.9
        // rather than an exception, so it has to be asserted explicitly.
        PhaseStats s = new PhaseStats("concurrent", 1_000_000L);
        int threads = 16, perThread = 5_000;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var latch = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 1; i <= perThread; i++) s.record(i * 1_000L);
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(s.raw().count()).isEqualTo((long) threads * perThread);
    }

    @Test
    void throttleAndRetryCountersAccumulate() {
        PhaseStats stats = new PhaseStats("LOAD", 1 * MS);
        assertThat(stats.throttles()).isZero();
        assertThat(stats.retries()).isZero();
        for (int i = 0; i < 3; i++) stats.recordThrottle();
        for (int i = 0; i < 7; i++) stats.recordRetry();
        assertThat(stats.throttles()).isEqualTo(3L);
        assertThat(stats.retries()).isEqualTo(7L);
    }

    @Test
    void hitAndMissCountersAccumulate() {
        PhaseStats stats = new PhaseStats("R-A", 1 * MS);
        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isZero();
        for (int i = 0; i < 4; i++) stats.recordHit();
        for (int i = 0; i < 9; i++) stats.recordMiss();
        assertThat(stats.hits()).isEqualTo(4L);
        assertThat(stats.misses()).isEqualTo(9L);
    }

    @Test
    void hitAndMissCountersLoseNoSamplesUnderConcurrentRecording() throws Exception {
        // See concurrentRecordingLosesNoSamples above: hits/misses feed task-17's miss-rate
        // validity check, so they are backed by AtomicLong (unlike throttles/retries) --
        // asserted explicitly here for the same reason.
        PhaseStats s = new PhaseStats("concurrent-miss", 1_000_000L);
        int threads = 16, perThread = 5_000;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var latch = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        s.recordHit();
                        s.recordMiss();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(s.hits()).isEqualTo((long) threads * perThread);
        assertThat(s.misses()).isEqualTo((long) threads * perThread);
    }

    @Test
    void rawHistogramIsExposedForIntervalLogging() {
        PhaseStats stats = uniformMillis();
        assertThat(stats.rawHistogram().getTotalCount()).isEqualTo(1_000L);
        assertThat(stats.rawHistogram().getNumberOfSignificantValueDigits()).isEqualTo(3);
    }

    @Test
    void phaseNameIsRetained() {
        assertThat(new PhaseStats("R-A", 1 * MS).phaseName()).isEqualTo("R-A");
    }

    @Test
    void reportsP95BetweenP90AndP99() {
        // P95 was missing from the summary while P50/P90/P99/P99.9 were present, so it had to be
        // recomputed from the per-request binary log after the fact. Recording it up front costs
        // one more histogram query and keeps every quoted percentile in one place.
        PhaseStats.Percentiles p = uniformMillis().raw();
        assertThat(p.p95()).isGreaterThan(p.p90()).isLessThan(p.p99());
        assertThat(p.p95()).isCloseTo(950_000.0, withinPercentage(1.0));    // microseconds
    }
}
