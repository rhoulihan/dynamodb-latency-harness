package com.rickh.ddblat.worker;

import com.rickh.ddblat.aws.AttemptCounter;
import com.rickh.ddblat.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public final class PutWorkload implements Workload {

    private final DynamoDbClient ddb;
    private final String table;
    private final KeySpace keys;
    private final ItemTemplateFactory factory;
    private final byte phaseId;

    public PutWorkload(DynamoDbClient ddb, String table, KeySpace keys,
                       ItemTemplateFactory factory, byte phaseId) {
        this.ddb = ddb;
        this.table = table;
        this.keys = keys;
        this.factory = factory;
        this.phaseId = phaseId;
    }

    @Override
    public Outcome execute(int index) {
        var item = factory.itemFor(keys.keyAt(index), ItemSizeModel.templateIndex(index));
        AttemptCounter.reset();
        long t0 = System.nanoTime();
        try {
            PutItemResponse resp = ddb.putItem(r -> r
                .tableName(table)
                .item(item)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
            long dt = System.nanoTime() - t0;
            double cu = resp.consumedCapacity() == null ? 0 : resp.consumedCapacity().capacityUnits();
            return new Outcome(dt, cu, AttemptCounter.attempts(), 0, false);
        } catch (Exception e) {
            return WorkloadErrors.classify(e, System.nanoTime() - t0, AttemptCounter.attempts());
        }
    }

    @Override
    public double estimatedCapacityUnits(int index) {
        return ItemSizeModel.writeCapacityUnits(ItemSizeModel.sizeForKey(index));
    }

    @Override
    public double maxCapacityUnits() {
        return ItemSizeModel.writeCapacityUnits(ItemSizeModel.ITEM_SIZE);
    }

    @Override
    public byte phaseId() {
        return phaseId;
    }
}
