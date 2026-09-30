package com.rickh.ddblat.worker;

import com.rickh.ddblat.model.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

class BatchWorkloadsTest {

    private static final KeySpace KEYS = new KeySpace(1 << 12);

    /** Captures requests; every response reports no consumed capacity (the fallback path). */
    private static final class FakeDdb implements DynamoDbClient {
        final List<BatchGetItemRequest> gets = new ArrayList<>();
        final List<BatchWriteItemRequest> writes = new ArrayList<>();
        final List<TransactWriteItemsRequest> txns = new ArrayList<>();
        boolean partial;
        RuntimeException txnFailure;

        @Override public String serviceName() { return "dynamodb"; }
        @Override public void close() { }

        @Override public BatchGetItemResponse batchGetItem(BatchGetItemRequest r) {
            gets.add(r);
            KeysAndAttributes ka = r.requestItems().values().iterator().next();
            String table = r.requestItems().keySet().iterator().next();
            var b = BatchGetItemResponse.builder().responses(Map.of(table, ka.keys()));
            if (partial) b.unprocessedKeys(Map.of(table, ka));
            return b.build();
        }
        @Override public BatchWriteItemResponse batchWriteItem(BatchWriteItemRequest r) {
            writes.add(r);
            var b = BatchWriteItemResponse.builder();
            if (partial) b.unprocessedItems(r.requestItems());
            return b.build();
        }
        @Override public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest r) {
            txns.add(r);
            if (txnFailure != null) throw txnFailure;
            return TransactWriteItemsResponse.builder().build();
        }
    }

    @Test
    void batchGetSendsDistinctKeysAndChargesPerItem() {
        var ddb = new FakeDdb();
        var w = new BatchGetWorkload(ddb, "t", KEYS, 100, true, (byte) 7, 10_000);
        var o = w.execute(0);
        var keys = ddb.gets.get(0).requestItems().get("t").keys();
        assertThat(keys).hasSize(100).doesNotHaveDuplicates();
        assertThat(ddb.gets.get(0).requestItems().get("t").consistentRead()).isTrue();
        assertThat(o.statusClass()).isZero();
        assertThat(o.itemFound()).isTrue();
        assertThat(o.consumedCu()).isEqualTo(300.0);                  // 100 x ceil(10000/4096)
        assertThat(new BatchGetWorkload(ddb, "t", KEYS, 100, false, (byte) 9, 10_000)
            .maxCapacityUnits()).isEqualTo(150.0);                    // eventual = half
    }

    @Test
    void consecutiveBatchesNeverOverlap() {
        var ddb = new FakeDdb();
        var w = new BatchGetWorkload(ddb, "t", KEYS, 100, true, (byte) 7, 523);
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < KEYS.size() / 100; i++) {
            w.execute(i);
            for (var k : ddb.gets.get(i).requestItems().get("t").keys()) {
                assertThat(seen.add(k.get("pk").s())).as("key reused within one pass").isTrue();
            }
        }
    }

    @Test
    void unprocessedKeysAreReportedAsAThrottleNotRetriedInsideTheSample() {
        var ddb = new FakeDdb();
        ddb.partial = true;
        var o = new BatchGetWorkload(ddb, "t", KEYS, 50, true, (byte) 7, 523).execute(0);
        assertThat(o.throttled()).isTrue();
        assertThat(o.statusClass()).isEqualTo(1);
        assertThat(ddb.gets).hasSize(1);
    }

    @Test
    void batchPutIsAFullTwentyFiveItemWriteAtTheConfiguredSize() {
        var ddb = new FakeDdb();
        var f = new ItemTemplateFactory(3L, 2_000);
        var o = new BatchPutWorkload(ddb, "t", KEYS, 25, f, (byte) 11).execute(0);
        var reqs = ddb.writes.get(0).requestItems().get("t");
        assertThat(reqs).hasSize(25);
        for (var wr : reqs) {
            assertThat(ItemTemplateFactory.computeItemSizeBytes(wr.putRequest().item())).isEqualTo(2_000);
        }
        assertThat(o.consumedCu()).isEqualTo(50.0);                   // 25 x 2 WCU
        assertThatThrownBy(() -> new BatchPutWorkload(ddb, "t", KEYS, 26, f, (byte) 11))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transactionsBillDoubleAndClampToTheFourMegabyteLimit() {
        var ddb = new FakeDdb();
        var small = new TransactWriteWorkload(ddb, "t", KEYS, 100, new ItemTemplateFactory(1L, 523), (byte) 13);
        assertThat(small.itemsPerTransaction()).isEqualTo(100);
        assertThat(small.maxCapacityUnits()).isEqualTo(200.0);         // 100 x 1 WCU x 2

        var big = new TransactWriteWorkload(ddb, "t", KEYS, 100, new ItemTemplateFactory(1L, 50_000), (byte) 13);
        assertThat(big.itemsPerTransaction()).isEqualTo(83);
        big.execute(0);
        assertThat(ddb.txns.get(0).transactItems()).hasSize(83);
        long bytes = ddb.txns.get(0).transactItems().stream()
            .mapToLong(t -> ItemTemplateFactory.computeItemSizeBytes(t.put().item())).sum();
        assertThat(bytes).isLessThanOrEqualTo(4L * 1024 * 1024);
    }

    @Test
    void aThrottledCancellationIsAThrottleAndAConflictIsAnError() {
        var ddb = new FakeDdb();
        var w = new TransactWriteWorkload(ddb, "t", KEYS, 10, new ItemTemplateFactory(1L, 523), (byte) 13);

        ddb.txnFailure = TransactionCanceledException.builder().message("cancelled")
            .cancellationReasons(CancellationReason.builder().code("ThrottlingError").build()).build();
        assertThat(w.execute(0).throttled()).isTrue();

        ddb.txnFailure = TransactionCanceledException.builder().message("cancelled")
            .statusCode(400)
            .cancellationReasons(CancellationReason.builder().code("TransactionConflict").build()).build();
        var o = w.execute(1);
        assertThat(o.throttled()).isFalse();
        assertThat(o.statusClass()).isEqualTo(2);
    }
}
