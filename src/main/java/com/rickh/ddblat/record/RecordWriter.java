package com.rickh.ddblat.record;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Single-writer sink for {@link RecordBuffer}s.
 *
 * Workers acquire a clean buffer, fill it, and submit it; one writer thread drains the submit
 * queue to a gzipped file, resets each drained buffer, and returns it to the free list. Every
 * buffer is allocated in the constructor, so the pool is a closed system: totalBuffers buffers
 * circulate between the free list, the workers, and the submit queue, and nothing is allocated
 * after startup.
 *
 * Handoff happens after the worker's closing nanoTime(), so the latency is already computed and
 * encoded before submit() is called. A stalled writer can therefore never inflate or corrupt a
 * recorded latency -- it can only block acquire(), which lowers achieved throughput, and that is
 * a signal the ramp controller already observes. A corrupted measurement would be invisible.
 *
 * Both queues are ArrayBlockingQueue; put/take under the same lock supply the happens-before
 * edge that makes a worker's plain writes into a RecordBuffer visible to the writer thread, and
 * the writer's reset() visible to the next worker that acquires it.
 *
 * One file for the whole run: phaseId lives inside each record, so downstream analysis splits by
 * phase rather than the harness rotating streams mid-window. Compression runs on the writer
 * thread only, never on a worker.
 *
 * Three failure modes are handled loudly rather than silently, because this runs unattended for
 * hours logging ~12.1M records and a silent hang, silent record loss, or silent corruption is
 * the worst possible outcome:
 * <ul>
 *   <li>{@link #acquire()} polls instead of blocking forever, so a dead writer thread surfaces
 *       as an {@link IllegalStateException} instead of hanging every worker permanently.</li>
 *   <li>{@link #close()} waits (bounded) for every checked-out buffer to come back before
 *       shutting down, and reports rather than silently drops any that never do.</li>
 *   <li>{@link #submit(RecordBuffer)} rejects a buffer that is not currently checked out --
 *       most commonly a stale reference resubmitted a second time by a caller that lost track
 *       of ownership. Silently accepting it would let two different callers acquire() and
 *       write into the same RecordBuffer concurrently, and RecordBuffer is documented not
 *       thread-safe.</li>
 * </ul>
 */
public final class RecordWriter implements AutoCloseable {

    private static final int FILE_BUFFER_BYTES = 1 << 20;   // 1 MiB
    private static final int GZIP_BUFFER_BYTES = 1 << 16;   // 64 KiB
    private static final long ACQUIRE_POLL_MILLIS = 1_000;
    private static final long CLOSE_WAIT_POLL_MILLIS = 50;
    private static final Duration DEFAULT_CLOSE_WAIT_BUDGET = Duration.ofSeconds(30);

    private final Path outputFile;
    private final int totalBuffers;
    private final int recordsPerBuffer;
    private final long closeWaitBudgetNanos;
    private final ArrayBlockingQueue<RecordBuffer> freeList;
    private final ArrayBlockingQueue<RecordBuffer> submitted;
    private final AtomicLong written = new AtomicLong();
    private final Thread writerThread;

    /**
     * Identity set (never .equals()) of buffers currently checked out to a caller, i.e.
     * returned by {@link #acquire()} but not yet handed back via {@link #submit(RecordBuffer)}.
     * Guards against a caller-side bug submitting a buffer it no longer owns -- e.g. a worker
     * interrupted between submitting a full buffer and acquiring its replacement, which could
     * otherwise resubmit the SAME buffer a second time from a stale local variable. A second
     * submit would let two different callers acquire() and write into the same RecordBuffer
     * concurrently, and RecordBuffer is documented not thread-safe. This is off the measured
     * request path -- acquire()/submit() are called once per buffer-full, not once per record --
     * so a synchronized set lookup here costs nothing that matters.
     */
    private final Set<RecordBuffer> checkedOut =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /** Shutdown sentinel, compared by reference. Never carries records. */
    private final RecordBuffer poisonPill = new RecordBuffer(1);

    private volatile Throwable failure;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile long buffersAbandonedAtClose;

    public RecordWriter(Path outputFile, int totalBuffers, int recordsPerBuffer) {
        this(outputFile, totalBuffers, recordsPerBuffer, DEFAULT_CLOSE_WAIT_BUDGET);
    }

    /**
     * Test-only seam: lets a test shrink the close() drain-wait budget from the 30s
     * production default so a test that deliberately abandons a buffer can observe the
     * timeout-then-proceed behavior without waiting 30 real seconds. Package-private:
     * production code always goes through the three-arg constructor above.
     */
    RecordWriter(Path outputFile, int totalBuffers, int recordsPerBuffer, Duration closeWaitBudget) {
        if (outputFile == null) {
            throw new IllegalArgumentException("outputFile must not be null");
        }
        if (totalBuffers <= 0) {
            throw new IllegalArgumentException("totalBuffers must be positive, got " + totalBuffers);
        }
        if (recordsPerBuffer <= 0) {
            throw new IllegalArgumentException("recordsPerBuffer must be positive, got " + recordsPerBuffer);
        }
        if (closeWaitBudget == null || closeWaitBudget.isNegative()) {
            throw new IllegalArgumentException("closeWaitBudget must not be null or negative");
        }
        this.outputFile = outputFile;
        this.totalBuffers = totalBuffers;
        this.recordsPerBuffer = recordsPerBuffer;
        this.closeWaitBudgetNanos = closeWaitBudget.toNanos();
        this.freeList = new ArrayBlockingQueue<>(totalBuffers);
        // +1 so the poison pill always has a slot even with every buffer in flight.
        this.submitted = new ArrayBlockingQueue<>(totalBuffers + 1);
        for (int i = 0; i < totalBuffers; i++) {
            this.freeList.add(new RecordBuffer(recordsPerBuffer));
        }
        this.writerThread = new Thread(this::drainLoop, "ddblat-record-writer");
        this.writerThread.setDaemon(true);
    }

    /** Starts the writer thread. Calling twice is a no-op. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        writerThread.start();
    }

    /**
     * Takes a clean buffer. Blocks while every buffer is in flight, but polls rather than
     * waiting forever: every {@code ACQUIRE_POLL_MILLIS} it checks whether the writer thread
     * is still alive and healthy. If the writer has died (disk full, IO error -- caught in
     * {@link #drainLoop()}) it will never return another buffer to the free list, so a plain
     * {@code take()} here would hang the calling worker forever with no diagnostic. Instead
     * this throws loudly.
     */
    public RecordBuffer acquire() throws InterruptedException {
        while (true) {
            RecordBuffer buf = freeList.poll(ACQUIRE_POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (buf != null) {
                buf.reset();
                checkedOut.add(buf);
                return buf;
            }
            if (failure != null || !writerThread.isAlive()) {
                throw new IllegalStateException("record writer thread died; cannot acquire buffer", failure);
            }
        }
    }

    /**
     * Hands a filled buffer to the writer. Never blocks: the submit queue is sized to hold
     * every buffer at once, so the offer cannot fail unless a buffer was submitted twice.
     *
     * Rejects a buffer that is not currently checked out -- most commonly a stale reference
     * resubmitted after it was already handed to the writer once. See {@link #checkedOut}.
     */
    public void submit(RecordBuffer buf) {
        if (buf == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (!checkedOut.remove(buf)) {
            throw new IllegalStateException(
                "buffer submitted while not checked out -- double-submit, or a buffer this "
                    + "RecordWriter never handed out via acquire()?");
        }
        if (!submitted.offer(buf)) {
            throw new IllegalStateException("record writer queue is full; buffer submitted twice?");
        }
    }

    /** Records written to disk so far. Safe to read from any thread. */
    public long recordsWritten() {
        return written.get();
    }

    /**
     * Buffers that were still checked out by a worker -- acquired but never submitted -- when
     * {@link #close()} gave up waiting for them. Zero after a clean shutdown. A nonzero value
     * means every record still held in those buffers was dropped; {@link #close()} also prints
     * a warning to stderr when this happens.
     */
    public long buffersAbandonedAtClose() {
        return buffersAbandonedAtClose;
    }

    /**
     * Drains the queue, flushes and finalizes the gzip stream, joins the writer. Idempotent.
     *
     * <p><b>Contract:</b> every worker must {@link #submit(RecordBuffer)} its final, possibly
     * partial, buffer before close() is called -- a buffer still checked out by a worker is
     * invisible to the writer and cannot be flushed. close() waits up to the configured
     * close-wait budget (30s in production) for every outstanding buffer to come back to the
     * free list before sending the shutdown sentinel. If that budget expires with buffers still
     * outstanding, close() does not hang waiting indefinitely: it proceeds with shutdown,
     * records the count of abandoned buffers (see {@link #buffersAbandonedAtClose()}), and
     * prints a warning to stderr -- losing records must be loud, never silent.
     */
    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        if (!started) {
            return;
        }

        long deadline = System.nanoTime() + closeWaitBudgetNanos;
        while (freeList.size() < totalBuffers && System.nanoTime() < deadline) {
            Thread.sleep(CLOSE_WAIT_POLL_MILLIS);
        }
        long outstanding = totalBuffers - freeList.size();
        if (outstanding > 0) {
            buffersAbandonedAtClose = outstanding;
            System.err.println("RecordWriter.close(): " + outstanding
                + " buffer(s) were never submitted before close() and will be dropped -- up to "
                + (outstanding * (long) recordsPerBuffer) + " record(s) lost");
        }

        submitted.put(poisonPill);
        writerThread.join();
        if (failure != null) {
            throw new IllegalStateException("record writer failed", failure);
        }
    }

    private void drainLoop() {
        // Scratch lives on the writer thread, allocated once, so the bulk copy out of a direct
        // buffer costs nothing on any worker.
        byte[] scratch = new byte[recordsPerBuffer * LatencyRecord.BYTES];
        try (OutputStream out = new GZIPOutputStream(
                 new BufferedOutputStream(new FileOutputStream(outputFile.toFile()), FILE_BUFFER_BYTES),
                 GZIP_BUFFER_BYTES)) {
            while (true) {
                RecordBuffer buf = submitted.take();
                if (buf == poisonPill) {
                    break;
                }
                ByteBuffer view = buf.readOnlyView();
                int bytes = view.remaining();
                view.get(scratch, 0, bytes);
                out.write(scratch, 0, bytes);
                written.addAndGet(bytes / LatencyRecord.BYTES);
                buf.reset();
                freeList.put(buf);
            }
            out.flush();
        } catch (Throwable t) {
            failure = t;
        }
    }
}
