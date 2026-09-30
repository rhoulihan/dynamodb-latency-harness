package com.rickh.ddblat.worker;

import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;

import java.util.List;

/**
 * Batch and transaction APIs return a LIST of ConsumedCapacity, one per table touched. Sums the
 * reported units; falls back to the size model when nothing usable is reported -- the same rule
 * Get/Put follow for Oracle's endpoint, which returns the objects with a null units field.
 */
final class BatchCapacity {

    private BatchCapacity() {}

    static double sum(List<ConsumedCapacity> list, double modelFallback) {
        if (list == null || list.isEmpty()) return modelFallback;
        double total = 0;
        boolean any = false;
        for (ConsumedCapacity cc : list) {
            if (cc != null && cc.capacityUnits() != null) {
                total += cc.capacityUnits();
                any = true;
            }
        }
        return any ? total : modelFallback;
    }
}
