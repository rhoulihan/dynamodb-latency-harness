package com.rickh.ddblat.phase;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Selects a subset of key indices that live on a known slice of DynamoDB's hash space, so a
 * read phase can drive one thin slice of partitions at full rate instead of spreading evenly
 * across all of them.
 *
 * Restricting the *index* range does not work: DynamoDB assigns partitions by hashing the key,
 * so any contiguous or arbitrary subset of indices still spreads uniformly across every
 * partition (verified against this table: hottest partition +1.03% for any index subset tried).
 * A parallel {@code Scan} is different -- {@code Segment}/{@code TotalSegments} divides the
 * table into equal, contiguous slices of the actual hash key space, so segment {@code i} of
 * {@code N} is a real, known slice of the key space, not a guess at the hash function. This is
 * the same technique as the account owner's 2020 {@code rhoulihan/dynamodb-office-hours}
 * project ({@code TableLoader/src/com/amazonaws/TableLoader/RunScan.java}), ported from SDK v1's
 * {@code ScanSpec} to SDK v2's {@code Segment}/{@code TotalSegments} scan request fields.
 *
 * Only {@code segmentsUsed} of the table's {@code segmentsTotal} segments are scanned (one
 * thread per segment), so the selected keys are confined to a {@code segmentsUsed /
 * segmentsTotal} fraction of the table's partitions -- concentrating read load onto those
 * partitions instead of spreading it over all of them. The scan reads only the partition key
 * ({@code ProjectionExpression=pk}, eventually consistent, DynamoDB's Scan default) to keep
 * network transfer down to a few MB instead of the multi-GiB full-item cost that a wider scan
 * would otherwise represent -- consumed *capacity* is still billed on full item size regardless
 * of projection, only the bytes returned over the wire shrink.
 *
 * IMPORTANT: this selection runs once, before R-A/R-B's measurement windows open (see {@code
 * Main}), specifically so its own consumed capacity is never counted as part of either window's
 * achieved-rate or throttle numbers. Do not move this call inside a measurement window.
 */
public final class SegmentKeySelector {

    /**
     * If the selector returns fewer than this fraction of the expected key count
     * ({@code itemCount * segmentsUsed / segmentsTotal}), something is wrong with the scan or
     * the pk parsing -- proceeding would concentrate the configured read rate onto a far
     * smaller key set than intended, driving partitions well past the 3,000 RCU/s hard limit
     * and producing throttling that would look like a DynamoDB problem rather than a harness
     * bug. See the class javadoc's "fail loudly" guard rail.
     */
    static final double UNDER_COUNT_GUARD_FRACTION = 0.50;

    /** One page of one segment's scan: the pk values found, the continuation key (null when the
     * segment is exhausted), and consumed capacity for that page if the caller requested it. */
    public record ScanPage(List<String> pks, Map<String, AttributeValue> lastEvaluatedKey,
                           Double consumedCapacityUnits) {}

    /** The final, combined selection. Order of {@code indices} is unspecified -- the read cycle
     * that consumes it randomizes anyway. */
    public record Result(int[] indices, int segmentsUsed, int segmentsTotal,
                         long elapsedNanos, double consumedCapacityUnits) {}

    /**
     * Fetches one page of one segment. Isolated behind this interface so {@link
     * #runSelection} -- the pagination, parsing, aggregation, and guard-rail logic -- is
     * unit-testable with a fake, without a real DynamoDB Scan (which this project cannot
     * exercise outside AWS).
     */
    @FunctionalInterface
    public interface SegmentScanner {
        ScanPage scanPage(int segment, int totalSegments, Map<String, AttributeValue> exclusiveStartKey);
    }

    private record SegmentResult(List<Integer> indices, double consumedCapacityUnits) {}

    private SegmentKeySelector() {}

    /**
     * Real entry point: runs a parallel Scan against {@code table}, one thread per segment
     * {@code 0 .. segmentsUsed-1} out of {@code segmentsTotal}, projecting only {@code pk}.
     */
    public static Result select(DynamoDbClient ddb, String table, int itemCount,
                                int segmentsUsed, int segmentsTotal) {
        SegmentScanner scanner = (segment, total, exclusiveStartKey) -> {
            ScanRequest.Builder b = ScanRequest.builder()
                .tableName(table)
                .segment(segment)
                .totalSegments(total)
                // Only the partition key: drops network transfer from ~44 GiB to a few MB at
                // this project's 59 KiB item size. Consumed capacity is still billed on full
                // item size regardless -- the projection only shrinks bytes over the wire.
                .projectionExpression("pk")
                // Eventually consistent is Scan's default; set explicitly so the intent (half
                // the capacity cost of a strong scan) reads directly off this call.
                .consistentRead(false)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
            if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
                b.exclusiveStartKey(exclusiveStartKey);
            }
            ScanResponse resp = ddb.scan(b.build());
            List<String> pks = new ArrayList<>(resp.items().size());
            for (Map<String, AttributeValue> item : resp.items()) {
                AttributeValue v = item.get("pk");
                if (v != null && v.s() != null) pks.add(v.s());
            }
            Double cu = resp.consumedCapacity() == null ? null : resp.consumedCapacity().capacityUnits();
            return new ScanPage(pks, resp.hasLastEvaluatedKey() ? resp.lastEvaluatedKey() : null, cu);
        };
        return runSelection(scanner, itemCount, segmentsUsed, segmentsTotal);
    }

    /**
     * Everything except the actual DynamoDB round trip: pagination, {@code pk} parsing,
     * aggregation across segments, logging, and the under-count/zero-count guard rails.
     * {@code scanner} can be a canned function of segment/page in tests.
     */
    static Result runSelection(SegmentScanner scanner, int itemCount, int segmentsUsed,
                               int segmentsTotal) {
        long t0 = System.nanoTime();
        List<SegmentResult> perSegment;
        ExecutorService pool = Executors.newFixedThreadPool(segmentsUsed);
        try {
            List<Future<SegmentResult>> futures = new ArrayList<>(segmentsUsed);
            for (int seg = 0; seg < segmentsUsed; seg++) {
                final int segment = seg;
                futures.add(pool.submit(() -> scanSegment(scanner, segment, segmentsTotal)));
            }
            perSegment = new ArrayList<>(segmentsUsed);
            for (Future<SegmentResult> f : futures) {
                try {
                    perSegment.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while scanning segments", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(
                        "segment scan failed: " + e.getCause(), e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }

        int totalKeys = 0;
        double consumedCu = 0;
        for (SegmentResult r : perSegment) {
            totalKeys += r.indices().size();
            consumedCu += r.consumedCapacityUnits();
        }
        int[] indices = new int[totalKeys];
        int p = 0;
        for (SegmentResult r : perSegment) {
            for (int idx : r.indices()) indices[p++] = idx;
        }
        long elapsedNanos = System.nanoTime() - t0;

        long expectedKeys = (long) itemCount * segmentsUsed / segmentsTotal;
        System.out.printf(
            "SEGMENT-KEY-SELECT segmentsUsed=%d segmentsTotal=%d keysFound=%d expectedKeys=%d "
            + "elapsedMs=%d consumedCapacityUnits=%.1f%n",
            segmentsUsed, segmentsTotal, indices.length, expectedKeys, elapsedNanos / 1_000_000L,
            consumedCu);

        if (indices.length == 0) {
            throw new IllegalStateException(
                "SegmentKeySelector found zero keys for segmentsUsed=" + segmentsUsed + "/"
                + segmentsTotal + " (expected ~" + expectedKeys + ") -- aborting the run rather "
                + "than reading an empty key set");
        }
        if (indices.length < UNDER_COUNT_GUARD_FRACTION * expectedKeys) {
            throw new IllegalStateException(String.format(
                "SegmentKeySelector found %d keys, expected ~%d (segmentsUsed=%d/%d) -- fewer "
                + "than %.0f%% of expected. This means the scan or the pk parsing is wrong: "
                + "proceeding would concentrate the configured read rate onto a far smaller key "
                + "set than intended, driving partitions well past the 3,000 RCU/s limit and "
                + "producing throttling that looks like a DynamoDB problem. Aborting instead.",
                indices.length, expectedKeys, segmentsUsed, segmentsTotal,
                100 * UNDER_COUNT_GUARD_FRACTION));
        }

        return new Result(indices, segmentsUsed, segmentsTotal, elapsedNanos, consumedCu);
    }

    private static SegmentResult scanSegment(SegmentScanner scanner, int segment, int totalSegments) {
        List<Integer> indices = new ArrayList<>();
        double consumedCu = 0;
        Map<String, AttributeValue> exclusiveStartKey = null;
        do {
            ScanPage page = scanner.scanPage(segment, totalSegments, exclusiveStartKey);
            for (String pk : page.pks()) {
                indices.add(parseIndex(pk));
            }
            if (page.consumedCapacityUnits() != null) consumedCu += page.consumedCapacityUnits();
            exclusiveStartKey = page.lastEvaluatedKey();
        } while (exclusiveStartKey != null);
        return new SegmentResult(indices, consumedCu);
    }

    /** Parses a {@code K#%08d}-formatted pk (see {@code KeySpace}) back to its integer index. */
    static int parseIndex(String pk) {
        if (pk == null || !pk.startsWith("K#")) {
            throw new IllegalStateException("unparseable pk from scan (expected \"K#\" prefix): " + pk);
        }
        try {
            return Integer.parseInt(pk.substring(2));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unparseable pk from scan: " + pk, e);
        }
    }
}
