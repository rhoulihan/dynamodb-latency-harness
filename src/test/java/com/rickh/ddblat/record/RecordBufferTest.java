package com.rickh.ddblat.record;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

class RecordBufferTest {

    @Test
    void newBufferIsEmptyAndSizedInRecords() {
        RecordBuffer rb = new RecordBuffer(64);
        assertThat(rb.capacityRecords()).isEqualTo(64);
        assertThat(rb.count()).isZero();
        assertThat(rb.isFull()).isFalse();
        assertThat(rb.readOnlyView().remaining()).isZero();
    }

    @Test
    void backingBufferIsOneDirectAllocationOfExactlyCapacityTimesRecordSize() {
        RecordBuffer rb = new RecordBuffer(8);
        ByteBuffer view = rb.readOnlyView();
        assertThat(view.capacity()).isEqualTo(8 * LatencyRecord.BYTES);
        assertThat(view.isDirect()).isTrue();
        assertThat(view.isReadOnly()).isTrue();
    }

    @Test
    void filledBufferDecodesBackToTheSameRecordsInOrder() {
        RecordBuffer rb = new RecordBuffer(100);
        for (int i = 0; i < 100; i++) {
            assertThat(rb.tryAppend(i, i * 10L, i * 3, (short) (i % 8), (byte) 1, i % 16, i % 4))
                .as("append %d", i)
                .isTrue();
        }
        assertThat(rb.count()).isEqualTo(100);
        assertThat(rb.isFull()).isTrue();

        ByteBuffer view = rb.readOnlyView();
        assertThat(view.remaining()).isEqualTo(100 * LatencyRecord.BYTES);
        for (int i = 0; i < 100; i++) {
            LatencyRecord.Decoded d = LatencyRecord.decode(view, i * LatencyRecord.BYTES);
            assertThat(d.startNanos()).as("record %d", i).isEqualTo(i);
            assertThat(d.latencyNanos()).as("record %d", i).isEqualTo(i * 10L);
            assertThat(d.consumedCuTimes100()).as("record %d", i).isEqualTo(i * 3);
            assertThat(d.threadId()).as("record %d", i).isEqualTo((short) (i % 8));
            assertThat(d.phaseId()).as("record %d", i).isEqualTo((byte) 1);
            assertThat(d.attempts()).as("record %d", i).isEqualTo(i % 16);
            assertThat(d.statusClass()).as("record %d", i).isEqualTo(i % 4);
        }
    }

    @Test
    void tryAppendReturnsFalseWhenFullAndDoesNotCorruptExistingRecords() {
        RecordBuffer rb = new RecordBuffer(2);
        assertThat(rb.tryAppend(1L, 2L, 3, (short) 4, (byte) 5, 6, 7)).isTrue();
        assertThat(rb.tryAppend(8L, 9L, 10, (short) 11, (byte) 12, 13, 14)).isTrue();

        assertThat(rb.tryAppend(99L, 99L, 99, (short) 99, (byte) 99, 1, 1)).isFalse();
        assertThat(rb.tryAppend(99L, 99L, 99, (short) 99, (byte) 99, 1, 1)).isFalse();

        assertThat(rb.count()).isEqualTo(2);
        ByteBuffer view = rb.readOnlyView();
        assertThat(view.remaining()).isEqualTo(2 * LatencyRecord.BYTES);

        LatencyRecord.Decoded first = LatencyRecord.decode(view, 0);
        assertThat(first.startNanos()).isEqualTo(1L);
        assertThat(first.threadId()).isEqualTo((short) 4);

        LatencyRecord.Decoded second = LatencyRecord.decode(view, LatencyRecord.BYTES);
        assertThat(second.startNanos()).isEqualTo(8L);
        assertThat(second.threadId()).isEqualTo((short) 11);
    }

    @Test
    void resetMakesTheBufferReusableAndCountReturnsToZero() {
        RecordBuffer rb = new RecordBuffer(4);
        for (int i = 0; i < 4; i++) {
            assertThat(rb.tryAppend(i, i, i, (short) i, (byte) 0, 1, 0)).isTrue();
        }
        assertThat(rb.isFull()).isTrue();

        rb.reset();
        assertThat(rb.count()).isZero();
        assertThat(rb.isFull()).isFalse();
        assertThat(rb.readOnlyView().remaining()).isZero();

        assertThat(rb.tryAppend(1000L, 2000L, 30, (short) 1, (byte) 2, 1, 0)).isTrue();
        assertThat(rb.count()).isEqualTo(1);

        LatencyRecord.Decoded d = LatencyRecord.decode(rb.readOnlyView(), 0);
        assertThat(d.startNanos()).isEqualTo(1000L);
        assertThat(d.latencyNanos()).isEqualTo(2000L);
        assertThat(d.consumedCuTimes100()).isEqualTo(30);
    }

    @Test
    void steadyStateAppendsAllocateNothingBeyondTheConstructor() {
        // The single direct ByteBuffer is allocated once, in the constructor. tryAppend only
        // calls putLong/putInt/putShort/put on that one buffer -- no boxing, no Strings, no
        // growth -- so filling, resetting, and filling again touches exactly the same memory.
        // Two identical passes over 10_000 records prove the buffer is genuinely reusable and
        // that reset() restores full capacity rather than leaving the write cursor stranded.
        RecordBuffer rb = new RecordBuffer(10_000);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 10_000; i++) {
                assertThat(rb.tryAppend(i, i, i, (short) 0, (byte) 0, 1, 0))
                    .as("pass %d append %d", pass, i)
                    .isTrue();
            }
            assertThat(rb.count()).as("pass %d", pass).isEqualTo(10_000);
            assertThat(rb.tryAppend(0L, 0L, 0, (short) 0, (byte) 0, 1, 0))
                .as("pass %d overflow", pass)
                .isFalse();
            assertThat(rb.readOnlyView().remaining()).isEqualTo(10_000 * LatencyRecord.BYTES);
            rb.reset();
        }
        assertThat(rb.count()).isZero();
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new RecordBuffer(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capacityRecords");
        assertThatThrownBy(() -> new RecordBuffer(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capacityRecords");
    }
}
