package com.rickh.ddblat.record;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.*;

class LatencyRecordTest {

    private static ByteBuffer newBuffer(int records) {
        return ByteBuffer.allocate(records * LatencyRecord.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void recordIsExactlyTwentyFourBytes() {
        assertThat(LatencyRecord.BYTES).isEqualTo(24);
    }

    @Test
    void encodeThenDecodeRoundTripsEveryField() {
        ByteBuffer buf = newBuffer(1);
        LatencyRecord.encode(buf, 123_456_789L, 987_654L, 1_275, (short) 7, (byte) 2, 3, 1);

        LatencyRecord.Decoded d = LatencyRecord.decode(buf, 0);
        assertThat(d.startNanos()).isEqualTo(123_456_789L);
        assertThat(d.latencyNanos()).isEqualTo(987_654L);
        assertThat(d.consumedCuTimes100()).isEqualTo(1_275);
        assertThat(d.threadId()).isEqualTo((short) 7);
        assertThat(d.phaseId()).isEqualTo((byte) 2);
        assertThat(d.attempts()).isEqualTo(3);
        assertThat(d.statusClass()).isEqualTo(1);
    }

    @Test
    void roundTripsBoundaryValuesAtTheTopOfEveryField() {
        ByteBuffer buf = newBuffer(1);
        LatencyRecord.encode(buf, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE,
                             Short.MAX_VALUE, (byte) 127, 15, 15);

        LatencyRecord.Decoded d = LatencyRecord.decode(buf, 0);
        assertThat(d.startNanos()).isEqualTo(Long.MAX_VALUE);
        assertThat(d.latencyNanos()).isEqualTo(Long.MAX_VALUE);
        assertThat(d.consumedCuTimes100()).isEqualTo(Integer.MAX_VALUE);
        assertThat(d.threadId()).isEqualTo(Short.MAX_VALUE);
        assertThat(d.phaseId()).isEqualTo((byte) 127);
        assertThat(d.attempts()).isEqualTo(15);
        assertThat(d.statusClass()).isEqualTo(15);
    }

    @Test
    void roundTripsAnAllZeroRecord() {
        ByteBuffer buf = newBuffer(1);
        LatencyRecord.encode(buf, 0L, 0L, 0, (short) 0, (byte) 0, 0, 0);

        LatencyRecord.Decoded d = LatencyRecord.decode(buf, 0);
        assertThat(d.startNanos()).isZero();
        assertThat(d.latencyNanos()).isZero();
        assertThat(d.consumedCuTimes100()).isZero();
        assertThat(d.threadId()).isEqualTo((short) 0);
        assertThat(d.phaseId()).isEqualTo((byte) 0);
        assertThat(d.attempts()).isZero();
        assertThat(d.statusClass()).isZero();
    }

    @Test
    void encodeAdvancesPositionByExactlyTwentyFourBytes() {
        ByteBuffer buf = newBuffer(2);
        assertThat(buf.position()).isZero();

        LatencyRecord.encode(buf, 1L, 2L, 3, (short) 4, (byte) 5, 6, 7);
        assertThat(buf.position()).isEqualTo(24);

        LatencyRecord.encode(buf, 8L, 9L, 10, (short) 11, (byte) 12, 13, 14);
        assertThat(buf.position()).isEqualTo(48);
        assertThat(buf.remaining()).isZero();
    }

    @Test
    void decodeIsAbsoluteAndDoesNotMovePosition() {
        ByteBuffer buf = newBuffer(2);
        LatencyRecord.encode(buf, 1L, 2L, 3, (short) 4, (byte) 5, 6, 7);
        LatencyRecord.encode(buf, 100L, 200L, 300, (short) 8, (byte) 9, 10, 11);
        int positionBefore = buf.position();

        assertThat(LatencyRecord.decode(buf, LatencyRecord.BYTES).startNanos()).isEqualTo(100L);
        assertThat(LatencyRecord.decode(buf, 0).startNanos()).isEqualTo(1L);
        assertThat(buf.position()).isEqualTo(positionBefore);
    }

    @Test
    void fieldsSitAtTheDocumentedOffsets() {
        ByteBuffer buf = newBuffer(1);
        LatencyRecord.encode(buf, 1L, 2L, 3, (short) 4, (byte) 5, 6, 7);

        assertThat(buf.getLong(0)).isEqualTo(1L);
        assertThat(buf.getLong(8)).isEqualTo(2L);
        assertThat(buf.getInt(16)).isEqualTo(3);
        assertThat(buf.getShort(20)).isEqualTo((short) 4);
        assertThat(buf.get(22)).isEqualTo((byte) 5);
        assertThat(buf.get(23)).isEqualTo((byte) 0x67);   // (6 << 4) | 7
    }

    @Test
    void longsAreStoredLittleEndian() {
        ByteBuffer buf = newBuffer(1);
        LatencyRecord.encode(buf, 0x0102030405060708L, 0L, 0, (short) 0, (byte) 0, 0, 0);

        assertThat(buf.get(0)).isEqualTo((byte) 0x08);
        assertThat(buf.get(7)).isEqualTo((byte) 0x01);
    }

    @Test
    void packedNibblesSurviveTheHighBit() {
        ByteBuffer buf = newBuffer(1);
        LatencyRecord.encode(buf, 0L, 0L, 0, (short) 0, (byte) 0, 15, 15);

        assertThat(buf.get(23)).isEqualTo((byte) 0xFF);   // sign-extends if decoded carelessly
        LatencyRecord.Decoded d = LatencyRecord.decode(buf, 0);
        assertThat(d.attempts()).isEqualTo(15);
        assertThat(d.statusClass()).isEqualTo(15);
    }

    @Test
    void attemptsOutsideTheNibbleRangeIsRejectedWithoutWritingBytes() {
        ByteBuffer buf = newBuffer(1);
        assertThatThrownBy(() -> LatencyRecord.encode(buf, 1L, 2L, 3, (short) 4, (byte) 5, 16, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attempts");
        assertThat(buf.position()).isZero();

        assertThatThrownBy(() -> LatencyRecord.encode(buf, 1L, 2L, 3, (short) 4, (byte) 5, -1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attempts");
        assertThat(buf.position()).isZero();
    }

    @Test
    void statusClassOutsideTheNibbleRangeIsRejectedWithoutWritingBytes() {
        ByteBuffer buf = newBuffer(1);
        assertThatThrownBy(() -> LatencyRecord.encode(buf, 1L, 2L, 3, (short) 4, (byte) 5, 0, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("statusClass");
        assertThat(buf.position()).isZero();

        assertThatThrownBy(() -> LatencyRecord.encode(buf, 1L, 2L, 3, (short) 4, (byte) 5, 0, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("statusClass");
        assertThat(buf.position()).isZero();
    }
}
