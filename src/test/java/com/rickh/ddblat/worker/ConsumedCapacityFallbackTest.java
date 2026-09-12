package com.rickh.ddblat.worker;

import com.rickh.ddblat.model.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle's DynamoDB-compatible API (Autonomous AI Database) returns a null ConsumedCapacity on
 * every operation, even with ReturnConsumedCapacity=TOTAL -- measured 2026-09-11 against
 * ADB 26ai for PutItem, GetItem (strong and eventual), Scan and BatchWriteItem.
 *
 * The old code mapped null to 0.0. On that service every response would report zero consumed
 * capacity, CapacityMeter would see an achieved rate of zero, and the ramp controller would
 * drive the target upward forever chasing a number that can never rise -- a silent, total
 * failure of the pacing loop rather than a visible error.
 *
 * Falling back to the size model is exact rather than approximate here: every item is a fixed
 * 59 KiB, and the model was verified against real billed capacity on DynamoDB across 128 probe
 * items at 0.0 WCU deviation.
 */
class ConsumedCapacityFallbackTest {

    /** Returns responses with no ConsumedCapacity, the way Oracle's endpoint does. */
    private static final class NullCapacityDdb implements DynamoDbClient {
        @Override public String serviceName() { return "dynamodb"; }
        @Override public void close() { }
        @Override public PutItemResponse putItem(PutItemRequest r) {
            return PutItemResponse.builder().build();                 // consumedCapacity == null
        }
        // Oracle's actual shape: the object is present, the field inside is null.
        static PutItemResponse emptyCapacityObject() {
            return PutItemResponse.builder()
                .consumedCapacity(ConsumedCapacity.builder().build()).build();
        }
        @Override public GetItemResponse getItem(GetItemRequest r) {
            return GetItemResponse.builder()
                .item(java.util.Map.of("pk", AttributeValue.fromS("K#00000001"))).build();
        }
    }

    private static final KeySpace KEYS = new KeySpace(ItemSizeModel.ITEM_COUNT);

    @Test
    void putFallsBackToTheSizeModelWhenTheServiceOmitsConsumedCapacity() {
        var w = new PutWorkload(new NullCapacityDdb(), "t", KEYS,
                                new ItemTemplateFactory(1L), (byte) 1);
        Workload.Outcome o = w.execute(7);

        assertThat(o.statusClass()).isZero();
        assertThat(o.consumedCu())
            .as("null ConsumedCapacity must not be reported as zero consumption")
            .isEqualTo(w.estimatedCapacityUnits(7))
            .isEqualTo(59.0);
    }

    @Test
    void strongReadFallsBackToTheSizeModel() {
        var w = new GetWorkload(new NullCapacityDdb(), "t", KEYS, true, (byte) 3);
        Workload.Outcome o = w.execute(7);

        assertThat(o.consumedCu()).isEqualTo(w.estimatedCapacityUnits(7)).isEqualTo(15.0);
    }

    @Test
    void eventualReadFallbackIsHalfTheStrongCost() {
        var w = new GetWorkload(new NullCapacityDdb(), "t", KEYS, false, (byte) 5);
        Workload.Outcome o = w.execute(7);

        assertThat(o.consumedCu()).isEqualTo(w.estimatedCapacityUnits(7)).isEqualTo(7.5);
    }

    @Test
    void aReportedCapacityStillWinsOverTheFallback() {
        // AWS does report it; the fallback must never override a real measurement.
        DynamoDbClient reports = new DynamoDbClient() {
            @Override public String serviceName() { return "dynamodb"; }
            @Override public void close() { }
            @Override public PutItemResponse putItem(PutItemRequest r) {
                return PutItemResponse.builder().consumedCapacity(
                    ConsumedCapacity.builder().capacityUnits(123.0).build()).build();
            }
        };
        var w = new PutWorkload(reports, "t", KEYS, new ItemTemplateFactory(1L), (byte) 1);
        assertThat(w.execute(7).consumedCu()).isEqualTo(123.0);
    }

    @Test
    void handlesAConsumedCapacityObjectWhoseUnitsFieldIsNull() {
        // This is what Oracle actually returns. Checking only the object for null throws NPE on
        // unboxing, the workload classifies it as an error, and every request appears to fail
        // while the service is answering correctly -- observed on the first real OCI run.
        DynamoDbClient emptyObj = new DynamoDbClient() {
            @Override public String serviceName() { return "dynamodb"; }
            @Override public void close() { }
            @Override public PutItemResponse putItem(PutItemRequest r) {
                return NullCapacityDdb.emptyCapacityObject();
            }
        };
        var w = new PutWorkload(emptyObj, "t", KEYS, new ItemTemplateFactory(1L), (byte) 1);
        Workload.Outcome o = w.execute(7);

        assertThat(o.statusClass()).as("must not be classified as an error").isZero();
        assertThat(o.consumedCu()).isEqualTo(59.0);
    }
}
