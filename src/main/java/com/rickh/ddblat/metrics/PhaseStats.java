package com.rickh.ddblat.metrics;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-phase latency distribution: one raw histogram and one corrected for coordinated
 * omission.
 *
 * Open-loop pacing under-records a stall. While one request is blocked, the requests that
 * should have been issued behind it never are, so their latency never enters the histogram
 * and a multi-second stall shrinks to a single sample. recordValueWithExpectedInterval
 * back-fills the samples the pacing would have taken.
 *
 * Neither number is the whole truth -- the raw one understates a stall, the corrected one
 * synthesizes samples that were never measured -- so the harness reports both and never
 * publishes only one.
 *
 * Not thread-safe. Histogram is not, and a lock here would sit on the recording path;
 * this is fed by the single writer thread that drains the record queue.
 */
public final class PhaseStats {

    private static final long MAX_TRACKABLE_NANOS = 60_000_000_000L;   // 60 s
    private static final int  SIGNIFICANT_DIGITS  = 3;
    private static final double NANOS_PER_MICRO   = 1_000.0;

    /** All values in MICROSECONDS. */
    public record Percentiles(double p50, double p90, double p95, double p99, double p999,
                              double p9999, long max, double mean, double stddev, long count) {}

    private final String phaseName;
    private final long expectedIntervalNanos;
    // ConcurrentHistogram, not Histogram. Every worker thread calls record() on the same
    // instance; plain Histogram.recordValue is not thread-safe and silently loses counts
    // under contention, which would corrupt the headline percentiles rather than fail loudly.
    // ConcurrentHistogram extends Histogram, so rawHistogram() keeps its return type.
    private final Histogram rawHistogram       = new ConcurrentHistogram(1L, MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
    private final Histogram correctedHistogram = new ConcurrentHistogram(1L, MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);

    private long throttles;
    private long retries;
    // Atomic, unlike throttles/retries above: these feed task-17's miss-rate validity check
    // (see PhaseRunner), and an undercount here would itself hide the exact failure mode
    // this field exists to surface -- silently missing data that looks like a healthy run.
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public PhaseStats(String phaseName, long expectedIntervalNanos) {
        if (expectedIntervalNanos <= 0) {
            throw new IllegalArgumentException(
                "expectedIntervalNanos must be > 0, got " + expectedIntervalNanos);
        }
        this.phaseName = phaseName;
        this.expectedIntervalNanos = expectedIntervalNanos;
    }

    /** Clamped to the trackable range: an outlier past 60 s must not throw mid-run. */
    public void record(long latencyNanos) {
        long v = Math.min(Math.max(latencyNanos, 1L), MAX_TRACKABLE_NANOS);
        rawHistogram.recordValue(v);
        correctedHistogram.recordValueWithExpectedInterval(v, expectedIntervalNanos);
    }

    public void recordThrottle() {
        throttles++;
    }

    public void recordRetry() {
        retries++;
    }

    /** A successful read whose response actually contained the item. */
    public void recordHit() {
        hits.incrementAndGet();
    }

    /** A successful read whose response did not contain the item -- see task-17. */
    public void recordMiss() {
        misses.incrementAndGet();
    }

    public Percentiles raw() {
        return percentilesOf(rawHistogram);
    }

    public Percentiles corrected() {
        return percentilesOf(correctedHistogram);
    }

    public long throttles() {
        return throttles;
    }

    public long retries() {
        return retries;
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public String phaseName() {
        return phaseName;
    }

    /** Exposed so the run can emit interval .hlog output for post-hoc re-slicing. */
    public Histogram rawHistogram() {
        return rawHistogram;
    }

    private static Percentiles percentilesOf(Histogram h) {
        return new Percentiles(
            h.getValueAtPercentile(50.0)  / NANOS_PER_MICRO,
            h.getValueAtPercentile(90.0)  / NANOS_PER_MICRO,
            h.getValueAtPercentile(95.0)  / NANOS_PER_MICRO,
            h.getValueAtPercentile(99.0)  / NANOS_PER_MICRO,
            h.getValueAtPercentile(99.9)  / NANOS_PER_MICRO,
            h.getValueAtPercentile(99.99) / NANOS_PER_MICRO,
            h.getMaxValue() / 1_000L,
            h.getMean()          / NANOS_PER_MICRO,
            h.getStdDeviation()  / NANOS_PER_MICRO,
            h.getTotalCount());
    }
}
