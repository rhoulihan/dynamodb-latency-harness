package com.rickh.ddblat.worker;

import com.rickh.ddblat.aws.AttemptCounter;
import com.rickh.ddblat.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One full BatchWriteItem of {@code batchSize} PutRequests per call -- MELI's non-atomic "bulk"
 * writes, run at the API maximum of 25.
 *
 * Not atomic: each put succeeds or fails on its own, and a partially applied batch comes back as
 * UnprocessedItems rather than an exception. As with BatchGetItem that is the throttle signal,
 * and it is reported as one rather than silently retried inside the latency sample.
 */
public final class BatchPutWorkload implements Workload {

    public static final int MAX_BATCH = 25;

    private final DynamoDbClient ddb;
    private final String table;
    private final BatchKeys batch;
    private final ItemTemplateFactory factory;
    private final byte phaseId;

    public BatchPutWorkload(DynamoDbClient ddb, String table, KeySpace keys, int batchSize,
                            ItemTemplateFactory factory, byte phaseId) {
        if (batchSize > MAX_BATCH) {
            throw new IllegalArgumentException("BatchWriteItem takes at most 25 requests, got " + batchSize);
        }
        this.ddb = ddb;
        this.table = table;
        this.batch = new BatchKeys(keys, batchSize);
        this.factory = factory;
        this.phaseId = phaseId;
    }

    @Override
    public Outcome execute(int index) {
        int start = batch.nextBlockStart();
        List<WriteRequest> writes = new ArrayList<>(batch.batchSize());
        for (int i = 0; i < batch.batchSize(); i++) {
            int k = start + i;
            Map<String, AttributeValue> item =
                factory.itemFor(batch.keyAt(k), ItemSizeModel.templateIndex(k));
            writes.add(WriteRequest.builder().putRequest(p -> p.item(item)).build());
        }
        AttemptCounter.reset();
        long t0 = System.nanoTime();
        try {
            BatchWriteItemResponse resp = ddb.batchWriteItem(r -> r
                .requestItems(Map.of(table, writes))
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
            long dt = System.nanoTime() - t0;
            double cu = BatchCapacity.sum(resp.consumedCapacity(), maxCapacityUnits());
            boolean partial = resp.hasUnprocessedItems() && !resp.unprocessedItems().isEmpty();
            return partial
                ? new Outcome(dt, cu, AttemptCounter.attempts(), 1, true)
                : new Outcome(dt, cu, AttemptCounter.attempts(), 0, false);
        } catch (Exception e) {
            return WorkloadErrors.classify(e, System.nanoTime() - t0, AttemptCounter.attempts());
        }
    }

    @Override
    public double estimatedCapacityUnits(int index) {
        return maxCapacityUnits();
    }

    @Override
    public double maxCapacityUnits() {
        return (double) ItemSizeModel.writeCapacityUnits(factory.itemSize()) * batch.batchSize();
    }

    @Override
    public byte phaseId() {
        return phaseId;
    }
}
