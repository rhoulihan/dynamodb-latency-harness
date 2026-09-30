package com.rickh.ddblat.worker;

import com.rickh.ddblat.aws.AttemptCounter;
import com.rickh.ddblat.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One TransactWriteItems of up to 100 Puts per call -- MELI's "Batch (Atomic), lotes de hasta
 * 100 items". BatchWriteItem cannot do this: it caps at 25 and is not atomic.
 *
 * Two costs the size model must carry, both documented by AWS:
 *  - every item is written twice (prepare + commit), so a transaction bills 2x WCU;
 *  - the call is capped at 4 MB aggregate as well as 100 actions, so at 50 KB items the largest
 *    legal batch is 83. The constructor clamps rather than letting every call fail validation.
 *
 * A cancelled transaction is still billed. TransactionCanceledException whose reasons include a
 * throttling code is reported as a throttle; any other cancellation (a TransactionConflict from
 * overlapping keys, say) is a 4xx failure and counts against the error budget.
 */
public final class TransactWriteWorkload implements Workload {

    private final DynamoDbClient ddb;
    private final String table;
    private final BatchKeys batch;
    private final ItemTemplateFactory factory;
    private final byte phaseId;

    public TransactWriteWorkload(DynamoDbClient ddb, String table, KeySpace keys,
                                 int requestedItems, ItemTemplateFactory factory, byte phaseId) {
        int cap = ItemSizeModel.maxItemsPerTransaction(factory.itemSize());
        this.ddb = ddb;
        this.table = table;
        this.batch = new BatchKeys(keys, Math.min(requestedItems, cap));
        this.factory = factory;
        this.phaseId = phaseId;
    }

    /** Actions per transaction after the 100-action / 4 MB clamp. */
    public int itemsPerTransaction() {
        return batch.batchSize();
    }

    @Override
    public Outcome execute(int index) {
        int start = batch.nextBlockStart();
        List<TransactWriteItem> actions = new ArrayList<>(batch.batchSize());
        for (int i = 0; i < batch.batchSize(); i++) {
            int k = start + i;
            Map<String, AttributeValue> item =
                factory.itemFor(batch.keyAt(k), ItemSizeModel.templateIndex(k));
            actions.add(TransactWriteItem.builder()
                .put(p -> p.tableName(table).item(item)).build());
        }
        AttemptCounter.reset();
        long t0 = System.nanoTime();
        try {
            TransactWriteItemsResponse resp = ddb.transactWriteItems(r -> r
                .transactItems(actions)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
            long dt = System.nanoTime() - t0;
            double cu = BatchCapacity.sum(resp.consumedCapacity(), maxCapacityUnits());
            return new Outcome(dt, cu, AttemptCounter.attempts(), 0, false);
        } catch (TransactionCanceledException tce) {
            long dt = System.nanoTime() - t0;
            boolean throttled = tce.hasCancellationReasons() && tce.cancellationReasons().stream()
                .anyMatch(r -> r.code() != null && (r.code().contains("Throttl")
                    || r.code().contains("ProvisionedThroughputExceeded")));
            return throttled
                ? new Outcome(dt, 0, AttemptCounter.attempts(), 1, true)
                : WorkloadErrors.classify(tce, dt, AttemptCounter.attempts());
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
        return 2.0 * ItemSizeModel.writeCapacityUnits(factory.itemSize()) * batch.batchSize();
    }

    @Override
    public byte phaseId() {
        return phaseId;
    }
}
