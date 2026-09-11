package com.rickh.ddblat.record;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A pooled, fixed-capacity block of {@link LatencyRecord}s backed by exactly one direct
 * ByteBuffer, allocated once in the constructor and never replaced or grown.
 *
 * A worker holds one of these, appends to it until it is full, hands it to the RecordWriter,
 * and takes a clean one from the free list. Steady state therefore allocates nothing on the
 * measurement path: tryAppend is a bounds check plus five primitive puts.
 *
 * Direct rather than heap so the writer thread's bulk copy to the output stream does not have
 * to be pinned around a GC-movable byte[], and so ~290 MB of record traffic stays off the heap
 * ZGC is already absorbing 554 MB/s of SDK read-path garbage in.
 *
 * Not thread-safe by design: one buffer belongs to one worker at a time, and the handoff
 * through the writer's blocking queues supplies the happens-before edge.
 */
public final class RecordBuffer {

    private final ByteBuffer buf;
    private final int capacityRecords;
    private int count;

    public RecordBuffer(int capacityRecords) {
        if (capacityRecords <= 0) {
            throw new IllegalArgumentException("capacityRecords must be positive, got " + capacityRecords);
        }
        this.capacityRecords = capacityRecords;
        this.buf = ByteBuffer.allocateDirect(capacityRecords * LatencyRecord.BYTES)
                             .order(ByteOrder.LITTLE_ENDIAN);
        this.count = 0;
    }

    /**
     * Appends one record. Returns false when the buffer is full rather than throwing:
     * "full" is the normal, expected outcome once per buffer and is not an error, and
     * building an exception on the measurement path would allocate a stack trace.
     */
    public boolean tryAppend(long startNanos,
                             long latencyNanos,
                             int consumedCuTimes100,
                             short threadId,
                             byte phaseId,
                             int attempts,
                             int statusClass) {
        if (count == capacityRecords) {
            return false;
        }
        LatencyRecord.encode(buf, startNanos, latencyNanos, consumedCuTimes100,
                             threadId, phaseId, attempts, statusClass);
        count++;
        return true;
    }

    public int count() {
        return count;
    }

    public int capacityRecords() {
        return capacityRecords;
    }

    public boolean isFull() {
        return count == capacityRecords;
    }

    /** Rewinds for reuse. The backing memory is retained; only the write cursor moves. */
    public void reset() {
        buf.clear();
        count = 0;
    }

    /**
     * A flipped, read-only duplicate covering exactly the records written so far. The
     * duplicate has its own position and limit, so the writer thread can drain it without
     * disturbing the owning worker's cursor. Byte order is reapplied explicitly: duplicate()
     * and asReadOnlyBuffer() do not reliably carry it, and a big-endian view would decode
     * every field wrong while still looking structurally valid.
     */
    public ByteBuffer readOnlyView() {
        ByteBuffer view = buf.duplicate();
        view.order(ByteOrder.LITTLE_ENDIAN);
        view.position(0);
        view.limit(count * LatencyRecord.BYTES);
        ByteBuffer readOnly = view.asReadOnlyBuffer();
        readOnly.order(ByteOrder.LITTLE_ENDIAN);
        return readOnly;
    }
}
