package com.rickh.ddblat.rate;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Lock-free rate limiter denominated in DynamoDB capacity units per second.
 *
 * Requests do not cost the same: 59 WCU for a PutItem, 15 RCU for a strong GetItem,
 * 7.5 for an eventual one (every item is a fixed 59 KiB). Pacing on requests per
 * second would still be one arithmetic step removed from what the table is actually
 * provisioned in, so the bucket meters capacity directly instead.
 *
 * Tokens are held as micro-units (CU x 1000) in an AtomicLong: the contended path
 * CASes a long, never a double.
 *
 * A synchronized limiter was rejected. At 4,800 req/s across ~32 threads the monitor
 * would be entered 4,800 times a second, and block contention -- inflation, park,
 * unpark -- would surface as stalls at exactly the percentile being measured. The
 * instrument must not manufacture the tail it is reading.
 */
public final class TokenBucket implements AutoCloseable {

    /** Tokens are CU x 1000 so the CAS operates on a long. */
    private static final long MICROS_PER_UNIT   = 1_000L;
    private static final long REFILL_PERIOD_NANOS = 1_000_000L;      // 1 ms
    private static final long PARK_NANOS        = 50_000L;           // 50 us
    private static final long NANOS_PER_SECOND  = 1_000_000_000L;
    /** Sentinel for "not currently armed": nanoTime() may legitimately return 0. */
    private static final long NOT_AT_CAP        = Long.MIN_VALUE;

    private final double burstSeconds;
    private final double minCapacityUnits;

    private final AtomicLong tokenMicros    = new AtomicLong();
    private final AtomicLong rateMicros     = new AtomicLong();      // CU x 1000 per second
    private final AtomicLong capacityMicros = new AtomicLong();
    private final AtomicLong atCapSinceNanos = new AtomicLong(NOT_AT_CAP);

    private volatile boolean running;
    private Thread refiller;

    public TokenBucket(double unitsPerSecond, double burstSeconds) {
        this(unitsPerSecond, burstSeconds, 0.0);
    }

    /**
     * @param minCapacityUnits capacity floor, in CU: the bucket's capacity is never allowed
     *     to fall below this, even if {@code unitsPerSecond * burstSeconds} is smaller --
     *     see {@link #setRate}. Callers should pass the largest single request they will
     *     ever charge to this bucket (e.g. the max-size item's cost), so that request can
     *     never become permanently unsatisfiable. 0 disables the floor.
     */
    public TokenBucket(double unitsPerSecond, double burstSeconds, double minCapacityUnits) {
        if (unitsPerSecond <= 0) {
            throw new IllegalArgumentException("unitsPerSecond must be > 0, got " + unitsPerSecond);
        }
        if (burstSeconds <= 0) {
            throw new IllegalArgumentException("burstSeconds must be > 0, got " + burstSeconds);
        }
        if (minCapacityUnits < 0) {
            throw new IllegalArgumentException("minCapacityUnits must be >= 0, got " + minCapacityUnits);
        }
        this.burstSeconds = burstSeconds;
        this.minCapacityUnits = minCapacityUnits;
        setRate(unitsPerSecond);
        tokenMicros.set(capacityMicros.get());   // starts full: one burst of credit
    }

    /** Lifecycle only -- the synchronization here never touches the acquire path. */
    public synchronized void start() {
        if (running) return;
        running = true;
        refiller = new Thread(this::refillLoop, "ddblat-token-refill");
        refiller.setDaemon(true);
        refiller.start();
    }

    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;
        Thread t = refiller;
        refiller = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One CAS attempt. False means "no credit right now", never "wait". */
    public boolean tryAcquire(double units) {
        long need = microsFor(units);
        long cur = tokenMicros.get();
        if (cur < need) return false;
        if (!tokenMicros.compareAndSet(cur, cur - need)) return false;
        atCapSinceNanos.set(NOT_AT_CAP);
        return true;
    }

    /**
     * Blocks until the credit exists. Allocation-free: no lambdas, no boxing, no queue.
     *
     * Throws {@link IllegalStateException} rather than parking forever if {@code units}
     * can never fit in the bucket even when full -- checked both before the wait loop
     * (the common case: a caller mis-sized a bucket for the workload) and on every
     * iteration inside it, because {@link #setRate} can shrink the capacity out from
     * under an already-parked caller (a ramp throttling down mid-wait). A silent,
     * permanent park is never an acceptable outcome for either path.
     */
    public void acquire(double units) throws InterruptedException {
        long need = microsFor(units);
        checkSatisfiable(units, need);
        while (true) {
            if (Thread.interrupted()) throw new InterruptedException();
            long cur = tokenMicros.get();
            if (cur >= need && tokenMicros.compareAndSet(cur, cur - need)) {
                atCapSinceNanos.set(NOT_AT_CAP);
                return;
            }
            checkSatisfiable(units, need);
            // Park for roughly how long the deficit actually takes to accrue, rather than a
            // fixed short interval. A fixed spin is worst exactly when the target is far
            // below what the workers could consume -- which is the first minute of every
            // ramp -- and CPU burned pacing competes with the threads being measured.
            long deficit = need - Math.max(0L, cur);
            long rate = rateMicros.get();
            long waitNanos = rate <= 0 ? PARK_NANOS
                                       : (long) ((double) deficit / rate * 1_000_000_000.0);
            LockSupport.parkNanos(Math.clamp(waitNanos, PARK_NANOS, 10_000_000L));
        }
    }

    /** Throws if {@code units} can never be granted, i.e. exceeds the bucket even at full cap. */
    private void checkSatisfiable(double units, long need) {
        long cap = capacityMicros.get();
        if (need > cap) {
            throw new IllegalStateException("request of " + units + " CU can never be satisfied: "
                + "bucket capacity is " + (cap / (double) MICROS_PER_UNIT) + " CU (rate " + rate()
                + " CU/s x burst " + burstSeconds + "s). Raise burstSeconds or the minimum capacity.");
        }
    }

    /**
     * The ramp controller moves this; it takes effect on the refiller's next tick.
     *
     * Capacity is {@code max(unitsPerSecond * burstSeconds, minCapacityUnits)}: the floor
     * applies here too, not just in the constructor, so a ramp-down can never shrink the
     * bucket below the size of the largest request it must still satisfy.
     */
    public void setRate(double unitsPerSecond) {
        long r = Math.max(1L, Math.round(unitsPerSecond * MICROS_PER_UNIT));
        long computedCap = Math.round(unitsPerSecond * burstSeconds * MICROS_PER_UNIT);
        long floorCap = Math.round(minCapacityUnits * MICROS_PER_UNIT);
        long cap = Math.max(MICROS_PER_UNIT, Math.max(computedCap, floorCap));
        rateMicros.set(r);
        capacityMicros.set(cap);
        // A rate cut must not leave more credit in the bucket than the new burst allows.
        while (true) {
            long cur = tokenMicros.get();
            if (cur <= cap || tokenMicros.compareAndSet(cur, cap)) return;
        }
    }

    public double rate() {
        return rateMicros.get() / (double) MICROS_PER_UNIT;
    }

    /** A consistent {rate, capacity, available tokens} snapshot, in CU, for logging. */
    public record Snapshot(double rateUnitsPerSecond, double capacityUnits, double availableTokens) {}

    /**
     * Reads rate, capacity, and available tokens together as one snapshot rather than via
     * three separate getters, so a caller logging all three (e.g. {@code PhaseRunner}'s 1 Hz
     * tick) never mixes a pre-{@link #setRate} rate with a post-{@link #setRate} capacity or
     * vice versa. Allocates one record; called once per second, not per request, so that cost
     * is intentionally not optimized away.
     */
    public Snapshot snapshot() {
        long rateSnapshot = rateMicros.get();
        long capacitySnapshot = capacityMicros.get();
        long tokensSnapshot = tokenMicros.get();
        return new Snapshot(rateSnapshot / (double) MICROS_PER_UNIT,
            capacitySnapshot / (double) MICROS_PER_UNIT, tokensSnapshot / (double) MICROS_PER_UNIT);
    }

    /** Full bucket: tokens are being produced faster than workers consume them. */
    public boolean isAtCap() {
        return tokenMicros.get() >= capacityMicros.get();
    }

    /**
     * How long the bucket has been continuously full, in nanos, using the caller's clock.
     * The first poll of a full bucket arms the timer and returns 0; any acquire disarms it.
     * This is the client-saturation signal: sustained time at cap means no worker was free
     * to spend the credit, so the bottleneck is the client, not the table.
     */
    public long continuouslyAtCapNanos(long nowNanos) {
        if (!isAtCap()) {
            atCapSinceNanos.set(NOT_AT_CAP);
            return 0L;
        }
        long since = atCapSinceNanos.get();
        if (since == NOT_AT_CAP) {
            atCapSinceNanos.compareAndSet(NOT_AT_CAP, nowNanos);
            return 0L;
        }
        return Math.max(0L, nowNanos - since);
    }

    private static long microsFor(double units) {
        return (long) Math.ceil(units * MICROS_PER_UNIT);
    }

    /**
     * Adds rate/1000 units per millisecond. Credit is computed from elapsed nanos rather
     * than from tick count, so a descheduled refiller on a loaded machine still delivers
     * the configured rate instead of silently under-filling. The sub-nanosecond remainder
     * is carried forward so the bucket does not drift low over a long run.
     */
    private void refillLoop() {
        long last = System.nanoTime();
        long carry = 0L;
        while (running) {
            LockSupport.parkNanos(REFILL_PERIOD_NANOS);
            long now = System.nanoTime();
            long elapsed = now - last;
            if (elapsed <= 0) continue;
            last = now;
            // A long stall is not a credit windfall, and it also bounds the multiply below.
            if (elapsed > NANOS_PER_SECOND) elapsed = NANOS_PER_SECOND;
            long product = rateMicros.get() * elapsed + carry;   // <= 4e7 * 1e9, no overflow
            long delta = product / NANOS_PER_SECOND;
            carry = product - delta * NANOS_PER_SECOND;
            if (delta > 0) addTokens(delta);
        }
    }

    private void addTokens(long deltaMicros) {
        long cap = capacityMicros.get();
        while (true) {
            long cur = tokenMicros.get();
            if (cur >= cap) return;
            long next = Math.min(cap, cur + deltaMicros);
            if (tokenMicros.compareAndSet(cur, next)) return;
        }
    }
}
