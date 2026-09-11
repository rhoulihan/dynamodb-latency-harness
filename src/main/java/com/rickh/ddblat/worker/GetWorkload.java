package com.rickh.ddblat.worker;

import com.rickh.ddblat.aws.AttemptCounter;
import com.rickh.ddblat.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

public final class GetWorkload implements Workload {

    private final DynamoDbClient ddb;
    private final String table;
    private final KeySpace keys;
    private final boolean consistentRead;
    private final byte phaseId;

    public GetWorkload(DynamoDbClient ddb, String table, KeySpace keys,
                       boolean consistentRead, byte phaseId) {
        this.ddb = ddb;
        this.table = table;
        this.keys = keys;
        this.consistentRead = consistentRead;
        this.phaseId = phaseId;
    }

    @Override
    public Outcome execute(int index) {
        Map<String, AttributeValue> key = Map.of("pk", AttributeValue.fromS(keys.keyAt(index)));
        AttemptCounter.reset();
        long t0 = System.nanoTime();
        try {
            GetItemResponse resp = ddb.getItem(r -> r
                .tableName(table)
                .key(key)
                .consistentRead(consistentRead)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
            long dt = System.nanoTime() - t0;
            double cu = resp.consumedCapacity() == null ? 0 : resp.consumedCapacity().capacityUnits();
            // hasItem() is the SDK-idiomatic miss check (true only when "Item" was actually
            // present in the response, not merely an empty-but-set map) -- see task-17: a
            // miss must be counted as a miss, never mistaken for a 0-byte hit.
            return new Outcome(dt, cu, AttemptCounter.attempts(), 0, false, resp.hasItem());
        } catch (Exception e) {
            return WorkloadErrors.classify(e, System.nanoTime() - t0, AttemptCounter.attempts());
        }
    }

    @Override
    public double estimatedCapacityUnits(int index) {
        int size = ItemSizeModel.sizeForKey(index);
        return consistentRead
            ? ItemSizeModel.readCapacityUnitsStrong(size)
            : ItemSizeModel.readCapacityUnitsEventual(size);
    }

    @Override
    public double maxCapacityUnits() {
        return consistentRead
            ? ItemSizeModel.readCapacityUnitsStrong(ItemSizeModel.ITEM_SIZE)
            : ItemSizeModel.readCapacityUnitsEventual(ItemSizeModel.ITEM_SIZE);
    }

    @Override
    public byte phaseId() {
        return phaseId;
    }
}
