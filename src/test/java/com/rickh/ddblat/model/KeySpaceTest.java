package com.rickh.ddblat.model;

import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.assertj.core.api.Assertions.*;

class KeySpaceTest {

    @Test
    void keysAreZeroPaddedToEightDigits() {
        KeySpace ks = new KeySpace(1024);
        assertThat(ks.keyAt(0)).isEqualTo("K#00000000");
        assertThat(ks.keyAt(1)).isEqualTo("K#00000001");
        assertThat(ks.keyAt(1023)).isEqualTo("K#00001023");
    }

    @Test
    void fullKeySpaceFormatsTheLastKeyCorrectly() {
        KeySpace ks = new KeySpace(ItemSizeModel.ITEM_COUNT);
        assertThat(ks.size()).isEqualTo(2_097_152);
        assertThat(ks.keyAt(2_097_151)).isEqualTo("K#02097151");
    }

    @Test
    void keyAtReturnsAStableReferenceSoTheHotPathAllocatesNothing() {
        KeySpace ks = new KeySpace(1024);
        assertThat(ks.keyAt(7)).isSameAs(ks.keyAt(7));
    }

    @Test
    void cycleVisitsEveryIndexExactlyOnce() {
        KeySpace ks = new KeySpace(1024);
        BitSet seen = new BitSet(1024);
        for (long i = 0; i < 1024; i++) {
            int idx = ks.cycleIndex(i);
            assertThat(seen.get(idx)).as("index %d revisited at i=%d", idx, i).isFalse();
            seen.set(idx);
        }
        assertThat(seen.cardinality()).isEqualTo(1024);
    }

    @Test
    void cycleWrapsAfterExactlyOneFullPass() {
        KeySpace ks = new KeySpace(1024);
        assertThat(ks.cycleIndex(1024)).isEqualTo(ks.cycleIndex(0));
    }

    @Test
    void cycleIsFullPeriodOverTheRealKeySpace() {
        KeySpace ks = new KeySpace(ItemSizeModel.ITEM_COUNT);
        BitSet seen = new BitSet(ks.size());
        for (long i = 0; i < ks.size(); i++) seen.set(ks.cycleIndex(i));
        assertThat(seen.cardinality()).isEqualTo(ks.size());
    }

    @Test
    void multiplierIsOddSoTheCycleIsFullPeriod() {
        assertThat(KeySpace.P % 2).isEqualTo(1);
    }

    @Test
    void rejectsNonPowerOfTwoSizes() {
        assertThatThrownBy(() -> new KeySpace(1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("power of two");
    }
}
