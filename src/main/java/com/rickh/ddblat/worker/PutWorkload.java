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
            // Oracle's DynamoDB-compatible API returns a null ConsumedCapacity on every
            // operation even with ReturnConsumedCapacity=TOTAL (measured 2026-09-11, ADB 26ai).
            // Reporting that as 0 would make CapacityMeter see zero achieved throughput and the
            // ramp chase a number that can never rise. The size model is exact here -- fixed
            // 59 KiB items, verified against real billed capacity at 0.0 WCU deviation -- so
            // falling back to it is a substitution, not an estimate. A capacity the service
            // DOES report always wins.
            double cu = capacityOrModel(resp.consumedCapacity(), index);
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

    /**
     * Oracle returns a ConsumedCapacity OBJECT whose capacityUnits() field is null -- not a null
     * object. Checking only the object throws NullPointerException on unboxing, which the
     * workload then classifies as a generic error, so every request "fails" while the service is
     * actually answering correctly. Both shapes have to be treated as "not reported".
     */
    private double capacityOrModel(ConsumedCapacity cc, int index) {
        if (cc == null || cc.capacityUnits() == null) return estimatedCapacityUnits(index);
        return cc.capacityUnits();
    }
}
