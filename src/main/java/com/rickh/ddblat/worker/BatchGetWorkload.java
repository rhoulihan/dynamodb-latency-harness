package com.rickh.ddblat.worker;

import com.rickh.ddblat.aws.AttemptCounter;
import com.rickh.ddblat.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One BatchGetItem of {@code batchSize} distinct keys per request -- MELI's "bulk" and "batch"
 * reads. Latency is recorded per CALL, which is what the bulk SLA (AVG < 20 ms, P95 < 30 ms) is
 * stated against.
 *
 * UnprocessedKeys is the throttle signal for this API: DynamoDB does not raise
 * ProvisionedThroughputExceeded for a partially served batch, it quietly returns the keys it
 * skipped. Retrying them inside execute() would fold two round trips into one latency sample,
 * so a partial response is reported as a throttle instead -- which fails the zero-throttle
 * validity criterion, the honest outcome for a window the service could not serve.
 */
public final class BatchGetWorkload implements Workload {

    private final DynamoDbClient ddb;
    private final String table;
    private final BatchKeys batch;
    private final boolean consistentRead;
    private final byte phaseId;
    private final int itemSize;

    public BatchGetWorkload(DynamoDbClient ddb, String table, KeySpace keys, int batchSize,
                            boolean consistentRead, byte phaseId, int itemSize) {
        if (batchSize > 100) {
            throw new IllegalArgumentException("BatchGetItem takes at most 100 keys, got " + batchSize);
        }
        this.ddb = ddb;
        this.table = table;
        this.batch = new BatchKeys(keys, batchSize);
        this.consistentRead = consistentRead;
        this.phaseId = phaseId;
        this.itemSize = ItemSizeModel.validateItemSize(itemSize);
    }

    @Override
    public Outcome execute(int index) {
        int start = batch.nextBlockStart();
        List<Map<String, AttributeValue>> keyList = new ArrayList<>(batch.batchSize());
        for (int i = 0; i < batch.batchSize(); i++) {
            keyList.add(Map.of("pk", AttributeValue.fromS(batch.keyAt(start + i))));
        }
        KeysAndAttributes ka = KeysAndAttributes.builder()
            .keys(keyList).consistentRead(consistentRead).build();
        AttemptCounter.reset();
        long t0 = System.nanoTime();
        try {
            BatchGetItemResponse resp = ddb.batchGetItem(r -> r
                .requestItems(Map.of(table, ka))
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
            long dt = System.nanoTime() - t0;
            double cu = BatchCapacity.sum(resp.consumedCapacity(), maxCapacityUnits());
            boolean partial = resp.hasUnprocessedKeys() && !resp.unprocessedKeys().isEmpty();
            if (partial) {
                return new Outcome(dt, cu, AttemptCounter.attempts(), 1, true);
            }
            int returned = resp.hasResponses() && resp.responses().get(table) != null
                ? resp.responses().get(table).size() : 0;
            return new Outcome(dt, cu, AttemptCounter.attempts(), 0, false,
                returned == batch.batchSize());
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
        double perItem = consistentRead
            ? ItemSizeModel.readCapacityUnitsStrong(itemSize)
            : ItemSizeModel.readCapacityUnitsEventual(itemSize);
        return perItem * batch.batchSize();
    }

    @Override
    public byte phaseId() {
        return phaseId;
    }
}
