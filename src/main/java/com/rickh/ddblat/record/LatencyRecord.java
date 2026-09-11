package com.rickh.ddblat.record;

import java.nio.ByteBuffer;

/**
 * Fixed-width 24-byte binary layout for one measured request.
 *
 * This runs on the worker thread, immediately after the closing nanoTime(), so it must
 * allocate nothing: primitives only, no Strings, no boxing, no varargs. Formatted text
 * would mean ~12.1M Strings inside the measurement loop, which would corrupt the P99.9
 * by more than the SDK's own allocation does.
 *
 * <pre>
 * offset  0  int64   startNanos          (offset from run start)
 * offset  8  int64   latencyNanos
 * offset 16  int32   consumedCU x 100
 * offset 20  int16   threadId
 * offset 22  int8    phaseId
 * offset 23  int8    (attempts &lt;&lt; 4) | statusClass
 * </pre>
 *
 * attempts and statusClass share one byte as two nibbles, so both are limited to 0..15.
 * One phase id per record means the whole run goes to one file and downstream analysis
 * splits by phase rather than the harness juggling four output streams.
 */
public final class LatencyRecord {

    public static final int BYTES = 24;

    private static final int OFF_START_NANOS   = 0;
    private static final int OFF_LATENCY_NANOS = 8;
    private static final int OFF_CONSUMED_CU   = 16;
    private static final int OFF_THREAD_ID     = 20;
    private static final int OFF_PHASE_ID      = 22;
    private static final int OFF_PACKED        = 23;

    private LatencyRecord() {}

    /** One decoded record. Used by tests and offline analysis only, never on the hot path. */
    public record Decoded(long startNanos,
                          long latencyNanos,
                          int consumedCuTimes100,
                          short threadId,
                          byte phaseId,
                          int attempts,
                          int statusClass) {}

    /**
     * Writes one record at the buffer's current position and advances it by exactly
     * {@link #BYTES}. The caller must have reserved that much room; the range checks
     * happen before the first write so a rejected record never leaves a partial record
     * behind for the writer thread to ship.
     */
    public static void encode(ByteBuffer buf,
                              long startNanos,
                              long latencyNanos,
                              int consumedCuTimes100,
                              short threadId,
                              byte phaseId,
                              int attempts,
                              int statusClass) {
        if (attempts < 0 || attempts > 15) {
            throw new IllegalArgumentException("attempts must be 0..15, got " + attempts);
        }
        if (statusClass < 0 || statusClass > 15) {
            throw new IllegalArgumentException("statusClass must be 0..15, got " + statusClass);
        }
        buf.putLong(startNanos);
        buf.putLong(latencyNanos);
        buf.putInt(consumedCuTimes100);
        buf.putShort(threadId);
        buf.put(phaseId);
        buf.put((byte) ((attempts << 4) | statusClass));
    }

    /** Absolute read at {@code offset}. Does not touch the buffer's position or limit. */
    public static Decoded decode(ByteBuffer buf, int offset) {
        int packed = buf.get(offset + OFF_PACKED) & 0xFF;
        return new Decoded(
            buf.getLong(offset + OFF_START_NANOS),
            buf.getLong(offset + OFF_LATENCY_NANOS),
            buf.getInt(offset + OFF_CONSUMED_CU),
            buf.getShort(offset + OFF_THREAD_ID),
            buf.get(offset + OFF_PHASE_ID),
            packed >>> 4,
            packed & 0x0F);
    }
}
