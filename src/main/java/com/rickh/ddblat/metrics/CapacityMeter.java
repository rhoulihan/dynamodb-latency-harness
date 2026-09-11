package com.rickh.ddblat.metrics;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Sliding window of consumed capacity, held as a ring of fixed-width buckets.
 *
 * Fed from the real ConsumedCapacity.CapacityUnits on every response, so achieved
 * throughput is what DynamoDB charged rather than a request count multiplied by an
 * estimated per-request cost. Every item is a fixed 59 KiB, so an estimate would
 * actually match here -- but this stays measured, not assumed, for the same reason
 * the size-model probe exists: it is the real billed number, not a model of it.
 *
 * Production: windowNanos = 10s, bucketCount = 100 (100 ms buckets).
 */
public final class CapacityMeter {

    /** Units are held as CU x 1000 so the accumulator is a long. */
    private static final long MICROS_PER_UNIT = 1_000L;
    /** No bucket has ever been written. */
    private static final long UNUSED = Long.MIN_VALUE;

    private final long windowNanos;
    private final long bucketNanos;
    private final int bucketCount;
    private final double windowSeconds;

    private final AtomicLongArray unitMicros;
    private final AtomicLongArray bucketTick;

    public CapacityMeter(long windowNanos, int bucketCount) {
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("windowNanos must be > 0, got " + windowNanos);
        }
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be > 0, got " + bucketCount);
        }
        if (windowNanos % bucketCount != 0) {
            throw new IllegalArgumentException(
                "windowNanos must divide evenly into bucketCount buckets: " + windowNanos + " / " + bucketCount);
        }
        this.windowNanos = windowNanos;
        this.bucketCount = bucketCount;
        this.bucketNanos = windowNanos / bucketCount;
        this.windowSeconds = windowNanos / 1e9;
        this.unitMicros = new AtomicLongArray(bucketCount);
        this.bucketTick = new AtomicLongArray(bucketCount);
        reset();
    }

    /** Thread-safe. Called once per response from every worker thread. */
    public void record(long nowNanos, double consumedCu) {
        long tick = Math.floorDiv(nowNanos, bucketNanos);
        int slot = (int) Math.floorMod(tick, (long) bucketCount);
        rollIfStale(slot, tick);
        long micros = Math.round(consumedCu * MICROS_PER_UNIT);
        if (micros != 0) unitMicros.addAndGet(slot, micros);
    }

    /**
     * Throughput over the fixed window: everything currently inside it, divided by the full
     * window length. At steady state with all buckets populated this is exact. The bucket
     * still filling contributes only a fraction of a bucket's worth, so the reading runs
     * about 1/bucketCount low -- which is why production uses 100 buckets (~1% bias, well
     * inside the 2% agreement tolerance) rather than a coarse count.
     */
    public double unitsPerSecond(long nowNanos) {
        long tick = Math.floorDiv(nowNanos, bucketNanos);
        long oldest = tick - bucketCount + 1;
        long sum = 0;
        for (int i = 0; i < bucketCount; i++) {
            long t = bucketTick.get(i);
            if (t >= oldest && t <= tick) sum += unitMicros.get(i);
        }
        return (sum / (double) MICROS_PER_UNIT) / windowSeconds;
    }

    public void reset() {
        for (int i = 0; i < bucketCount; i++) {
            unitMicros.set(i, 0L);
            bucketTick.set(i, UNUSED);
        }
    }

    public long windowNanos() {
        return windowNanos;
    }

    /**
     * Claims a slot for a new tick. The winner subtracts the value it observed rather than
     * storing zero, so a concurrent record that already passed this check is not erased.
     */
    private void rollIfStale(int slot, long tick) {
        long seen = bucketTick.get(slot);
        while (seen < tick) {
            long stale = unitMicros.get(slot);
            if (bucketTick.compareAndSet(slot, seen, tick)) {
                if (stale != 0) unitMicros.addAndGet(slot, -stale);
                return;
            }
            seen = bucketTick.get(slot);
        }
    }
}
