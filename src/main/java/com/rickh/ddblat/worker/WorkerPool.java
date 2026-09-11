package com.rickh.ddblat.worker;

import com.rickh.ddblat.metrics.CapacityMeter;
import com.rickh.ddblat.metrics.PhaseStats;
import com.rickh.ddblat.record.RecordBuffer;
import com.rickh.ddblat.record.RecordWriter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dynamically-sized pool of worker threads that drain an {@link IndexSource} against a
 * {@link Workload}, paced by a {@link com.rickh.ddblat.rate.TokenBucket}.
 *
 * Phase ids are allocated so ramp traffic and window traffic are separable offline:
 * {@code 0} LOAD ramp, {@code 1} LOAD window, {@code 2} R-A ramp, {@code 3} R-A window,
 * {@code 4} R-B ramp, {@code 5} R-B window. Ramp samples are logged but never counted
 * toward a headline percentile.
 *
 * Each worker computes its own closing {@code now = System.nanoTime()} and feeds it to
 * {@link CapacityMeter#record} BEFORE doing anything that could block on the writer (a
 * full-buffer swap calls {@link RecordWriter#acquire()}, which can genuinely block). That
 * ordering protects the timestamp behind the achieved-throughput signal the ramp controller
 * reads: a full buffer or a stalled writer can only delay a worker's progress to its NEXT
 * request -- which lowers measured throughput, a signal the controller already sees -- and
 * can never retroactively move the timestamp already recorded for the request that just
 * completed.
 *
 * This ordering does NOT protect the latency histogram, and does not need to: {@link
 * Workload.Outcome#latencyNanos()} is computed entirely inside {@link Workload#execute},
 * independent of anything WorkerPool measures itself, so writer backpressure has no path to
 * it at all, appended before or after.
 */
public final class WorkerPool implements AutoCloseable {

    private final Workload workload;
    private final com.rickh.ddblat.rate.TokenBucket bucket;
    private final CapacityMeter meter;
    private final RecordWriter writer;
    private final IndexSource indices;
    private final int maxThreads;
    private final long runStartNanos;

    private volatile PhaseStats stats;
    private volatile byte phaseId;

    private final List<Thread> threads = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean exhausted = new AtomicBoolean(false);
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final int initialThreads;

    public WorkerPool(Workload workload, com.rickh.ddblat.rate.TokenBucket bucket,
                      CapacityMeter meter, PhaseStats stats, RecordWriter writer,
                      IndexSource indices, int initialThreads, int maxThreads, long runStartNanos) {
        this.workload = workload;
        this.bucket = bucket;
        this.meter = meter;
        this.stats = stats;
        this.writer = writer;
        this.indices = indices;
        this.initialThreads = initialThreads;
        this.maxThreads = maxThreads;
        this.runStartNanos = runStartNanos;
        this.phaseId = workload.phaseId();
    }

    public void start() {
        running.set(true);
        addThreads(initialThreads);
    }

    public synchronized void addThreads(int n) {
        for (int i = 0; i < n && threads.size() < maxThreads; i++) {
            short id = (short) threads.size();
            Thread t = new Thread(() -> runWorker(id), "ddblat-worker-" + id);
            t.setDaemon(true);
            threads.add(t);
            if (running.get()) t.start();
        }
    }

    private void runWorker(short threadId) {
        RecordBuffer buf;
        try {
            buf = writer.acquire();
        } catch (InterruptedException e) {
            // Nothing was ever acquired, so there is no buffer to own or submit: return
            // now, before the try/finally below, rather than letting a finally that
            // assumes buf is owned run against an unassigned one.
            Thread.currentThread().interrupt();
            return;
        }
        try {
            while (running.get()) {
                int index = indices.next();
                if (index < 0) { exhausted.set(true); return; }

                try {
                    bucket.acquire(workload.estimatedCapacityUnits(index));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                long start = System.nanoTime();
                Workload.Outcome o = workload.execute(index);
                // Everything below happens AFTER the measured interval closes.

                long now = System.nanoTime();
                meter.record(now, o.consumedCu());
                PhaseStats s = stats;
                s.record(o.latencyNanos());
                if (o.throttled()) s.recordThrottle();
                if (o.attempts() > 1) { s.recordRetry(); retried.incrementAndGet(); }
                // Hit/miss is only a meaningful verdict for a successful response (statusClass
                // 0): a throttle, error, or timeout is neither. See task-17.
                if (o.statusClass() == 0) {
                    if (o.itemFound()) s.recordHit(); else s.recordMiss();
                }
                completed.incrementAndGet();

                if (!buf.tryAppend(start - runStartNanos, o.latencyNanos(),
                        (int) Math.round(o.consumedCu() * 100), threadId, phaseId,
                        Math.min(o.attempts(), 15), o.statusClass())) {
                    writer.submit(buf);
                    // Ownership released the instant submit() returns: the writer now owns
                    // this buffer and may already have drained, reset, and returned it to
                    // the free list for another worker to acquire(). buf must not still
                    // reference it if the acquire() below is interrupted before reassigning
                    // buf -- otherwise the finally below would submit() this same buffer a
                    // SECOND time, and a second worker could later acquire() and write into
                    // it concurrently with whoever holds it now. RecordBuffer is documented
                    // not thread-safe, and this RecordWriter is one instance shared across
                    // LOAD, R-A and R-B, so that race could corrupt a later phase's records.
                    buf = null;
                    try {
                        buf = writer.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    buf.tryAppend(start - runStartNanos, o.latencyNanos(),
                        (int) Math.round(o.consumedCu() * 100), threadId, phaseId,
                        Math.min(o.attempts(), 15), o.statusClass());
                }
            }
        } finally {
            // buf is null only in the narrow window above, between submitting the old
            // buffer and acquiring its replacement -- nothing left to submit in that case.
            // The in-flight request itself is not lost: its PhaseStats entry already landed
            // above: only the raw record row for that one request is dropped from the
            // .bin.gz file, which is acceptable for a shutdown-time interrupt.
            if (buf != null) writer.submit(buf);
        }
    }

    public void setStats(PhaseStats s) { this.stats = s; }
    public void setPhaseId(byte p)     { this.phaseId = p; }

    public synchronized int threadCount() { return threads.size(); }
    public long completed()               { return completed.get(); }
    public long retriedRequests()         { return retried.get(); }
    public boolean exhausted()            { return exhausted.get(); }

    /** The pool's configured starting thread count, for phase-start logging. */
    public int initialThreadCount() { return initialThreads; }

    /** The pool's configured thread-count ceiling, for phase-start logging. */
    public int maxThreadCount() { return maxThreads; }

    /** The largest capacity units a single request from this phase's workload can ever cost. */
    public double maxCapacityUnits() { return workload.maxCapacityUnits(); }

    /** Threads still alive. Zero after a clean {@link #stop()}; used by tests to catch leaks. */
    public synchronized int liveThreadCount() {
        int n = 0;
        for (Thread t : threads) {
            if (t.isAlive()) n++;
        }
        return n;
    }

    /**
     * Waits up to {@code timeout} for every worker thread to terminate ON ITS OWN --
     * WITHOUT interrupting any of them, unlike {@link #stop()}. {@code running} and every
     * thread's interrupt status are left untouched, so a worker that has already dequeued a
     * valid index from the {@link IndexSource} (from before some OTHER worker's call to
     * {@code next()} returned -1 and flipped {@link #exhausted}) simply keeps running: it
     * finishes the request it is holding, loops back, discovers the exhausted source itself,
     * and exits cleanly -- exactly as if nothing had called this method at all.
     *
     * This exists because {@link #exhausted()} flips true the instant the LAST index is
     * dequeued, not when the last request COMPLETES: at that moment other workers can easily
     * still be holding their own already-dequeued index, most commonly parked in {@link
     * com.rickh.ddblat.rate.TokenBucket#acquire} waiting for their turn. Calling {@link
     * #stop()} immediately upon observing {@code exhausted()} -- which is exactly what a
     * naive control loop does -- interrupts those workers before they ever call {@link
     * Workload#execute}, silently abandoning whatever index each was holding. For LOAD, that
     * is a permanent hole in the dataset: a key that is never written but that the read
     * phases will nonetheless later query. See {@code PhaseRunner.run}'s exhaustion branch.
     *
     * Bounded by {@code timeout} so a request that can genuinely never complete (the ramp's
     * target rate too low to ever grant the tokens a stuck worker needs -- the same
     * pathological case {@link #stop()}'s own interrupt was added to escape) cannot hang the
     * phase forever; the caller decides what "abandoned after the timeout" means for it (see
     * {@code PhaseRunner}, which logs it loudly rather than treating it as ordinary shutdown).
     *
     * @return the number of worker threads still alive when {@code timeout} elapsed -- 0 in
     *     the common case where every already-dequeued request finished in time.
     */
    public int drain(Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        List<Thread> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(threads);
        }
        int stillAlive = 0;
        for (Thread t : snapshot) {
            long remainingMillis = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            try {
                t.join(remainingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Still have to account for every thread's final state below, interrupted or
                // not -- an early return here would under-report how many were left running.
            }
            if (t.isAlive()) stillAlive++;
        }
        return stillAlive;
    }

    /**
     * Signals every worker to stop, THEN interrupts them, THEN joins. The interrupt is not
     * optional: a worker parked in {@link com.rickh.ddblat.rate.TokenBucket#acquire} only
     * ever exits via getting tokens or via {@link InterruptedException} -- flipping {@code
     * running} to false does nothing for a thread that is not between iterations, and the
     * caller (see {@code Main}) closes the bucket immediately after this returns, so a
     * worker still parked on a now-closed bucket would otherwise spin-wake on a park/check
     * loop for the rest of the JVM's life, corrupting client CPU readings in every later
     * phase. {@code runWorker}'s catch blocks already convert the interrupt into a clean
     * return that still runs its {@code finally} and submits the partial buffer.
     */
    public void stop() {
        running.set(false);
        synchronized (this) {
            for (Thread t : threads) {
                t.interrupt();
            }
            for (Thread t : threads) {
                try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    @Override
    public void close() { stop(); }
}
