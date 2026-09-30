package com.rickh.ddblat.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * The MELI PoC needs latency at five item sizes, not one. Every item a factory builds must be
 * EXACTLY the configured size, because capacity pacing charges the token bucket from the size
 * model before the call -- an item a few bytes over a 1 KiB boundary bills a whole extra WCU,
 * and the achieved-rate criterion would then disagree with CloudWatch.
 */
class ItemSizeParameterTest {

    @ParameterizedTest
    @ValueSource(ints = {370, 523, 2_000, 10_000, 50_000, 60_416})
    void everyTemplateBuildsAtExactlyTheConfiguredSize(int size) {
        var f = new ItemTemplateFactory(7L, size);
        for (int j = 0; j < ItemSizeModel.TEMPLATE_COUNT; j += 5) {
            assertThat(ItemTemplateFactory.computeItemSizeBytes(f.itemFor("K#00000042", j)))
                .as("size %d template %d", size, j).isEqualTo(size);
        }
    }

    @Test
    void capacityUnitsAtTheMeliSizes() {
        // size -> strong RCU, WCU (DynamoDB rounds reads to 4 KB, writes to 1 KB)
        int[][] expect = {{370, 1, 1}, {523, 1, 1}, {2_000, 1, 2}, {10_000, 3, 10}, {50_000, 13, 49}};
        for (int[] e : expect) {
            assertThat(ItemSizeModel.readCapacityUnitsStrong(e[0])).as("RCU @%d", e[0]).isEqualTo(e[1]);
            assertThat(ItemSizeModel.writeCapacityUnits(e[0])).as("WCU @%d", e[0]).isEqualTo(e[2]);
        }
    }

    @Test
    void transactionBatchIsCappedByTheFourMegabyteLimitNotJustTheActionCount() {
        assertThat(ItemSizeModel.maxItemsPerTransaction(523)).isEqualTo(100);
        assertThat(ItemSizeModel.maxItemsPerTransaction(10_000)).isEqualTo(100);
        assertThat(ItemSizeModel.maxItemsPerTransaction(50_000)).isEqualTo(83);
        assertThat(ItemSizeModel.maxItemsPerTransaction(60_416)).isEqualTo(69);
    }

    @Test
    void sizesTheTemplateCannotHoldAreRejected() {
        assertThatThrownBy(() -> new ItemTemplateFactory(1L, ItemSizeModel.MIN_ITEM_SIZE - 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemTemplateFactory(1L, 400 * 1024 + 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatNoException().isThrownBy(() -> new ItemTemplateFactory(1L, ItemSizeModel.MIN_ITEM_SIZE));
    }
}
