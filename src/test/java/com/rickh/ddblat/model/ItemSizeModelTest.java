package com.rickh.ddblat.model;

import org.junit.jupiter.api.Test;
import static com.rickh.ddblat.model.ItemSizeModel.*;
import static org.assertj.core.api.Assertions.assertThat;

class ItemSizeModelTest {

    @Test
    void everyTemplateIsTheFixedItemSize() {
        for (int j = 0; j < TEMPLATE_COUNT; j++) {
            assertThat(sizeForTemplate(j)).as("template %d", j).isEqualTo(60_416);
            assertThat(sizeForTemplate(j)).isEqualTo(ITEM_SIZE);
        }
    }

    @Test
    void meanSizeAcrossTemplatesIsExactlyTheFixedSize() {
        long sum = 0;
        for (int j = 0; j < TEMPLATE_COUNT; j++) sum += sizeForTemplate(j);
        assertThat(sum).isEqualTo(15_466_496L);          // 256 * 60_416
        assertThat(sum / TEMPLATE_COUNT).isEqualTo(60_416L);
    }

    @Test
    void everyTemplateIsUsedExactlyEightThousandOneHundredNinetyTwoTimes() {
        int[] counts = new int[TEMPLATE_COUNT];
        for (long k = 0; k < ITEM_COUNT; k++) counts[templateIndex(k)]++;
        for (int j = 0; j < TEMPLATE_COUNT; j++) {
            assertThat(counts[j]).as("template %d", j).isEqualTo(ITEM_COUNT / TEMPLATE_COUNT);
        }
    }

    @Test
    void datasetTotalsExactlyOneHundredEighteenGibibytes() {
        long total = 0;
        for (long k = 0; k < ITEM_COUNT; k++) total += sizeForKey(k);
        assertThat(total).isEqualTo(DATASET_BYTES);
        assertThat(DATASET_BYTES).isEqualTo(118L * 1024 * 1024 * 1024);
        assertThat(DATASET_BYTES).isEqualTo(126_701_535_232L);
    }

    @Test
    void templateIndexIsAPermutationNotAnArithmeticProgression() {
        // consecutive keys must not walk the template space in fixed steps
        int step = templateIndex(1) - templateIndex(0);
        boolean constantStep = true;
        for (long k = 1; k < 64; k++) {
            if (templateIndex(k + 1) - templateIndex(k) != step) { constantStep = false; break; }
        }
        assertThat(constantStep).isFalse();
    }

    @Test
    void capacityUnitsMatchDynamoDbRoundingForTheFixedItemSize() {
        assertThat(writeCapacityUnits(60_416)).isEqualTo(59);          // ceil(60416/1024)
        assertThat(readCapacityUnitsStrong(60_416)).isEqualTo(15);     // ceil(60416/4096) = ceil(14.75)
        assertThat(readCapacityUnitsEventual(60_416)).isEqualTo(7.5);

        assertThat(writeCapacityUnits(ITEM_SIZE)).isEqualTo(59);
        assertThat(readCapacityUnitsStrong(ITEM_SIZE)).isEqualTo(15);
        assertThat(readCapacityUnitsEventual(ITEM_SIZE)).isEqualTo(7.5);
    }

    @Test
    void meanCapacityUnitsMatchTheSpecDerivedRates() {
        double wcu = 0, rcu = 0;
        for (int j = 0; j < TEMPLATE_COUNT; j++) {
            wcu += writeCapacityUnits(sizeForTemplate(j));
            rcu += readCapacityUnitsStrong(sizeForTemplate(j));
        }
        wcu /= TEMPLATE_COUNT;
        rcu /= TEMPLATE_COUNT;
        // No rounding spread any more: every template costs exactly the same, so the mean
        // is exact rather than close-to.
        assertThat(wcu).isEqualTo(59.0);
        assertThat(rcu).isEqualTo(15.0);
        assertThat(36_000 / wcu).isCloseTo(610.0, within(1.0));       // PutItem/s at 90% of 40,000
        assertThat(36_000 / rcu).isCloseTo(2400.0, within(1.0));      // strong GetItem/s
        assertThat(36_000 / (rcu / 2)).isCloseTo(4800.0, within(1.0)); // eventual GetItem/s
    }

    @Test
    void blobPayloadIsItemSizeMinusFixedOverhead() {
        assertThat(FIXED_OVERHEAD).isEqualTo(222);
        assertThat(blobPayloadBytes(60_416)).isEqualTo(60_194);
        assertThat(blobPayloadBytes(ITEM_SIZE)).isEqualTo(60_416 - 222);
    }

    private static org.assertj.core.data.Offset<Double> within(double d) {
        return org.assertj.core.data.Offset.offset(d);
    }
}
