package com.rickh.ddblat.phase;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link SegmentKeySelector}'s actual {@code Scan} call needs a real DynamoDB and is not
 * unit-testable here (see the class javadoc, and the task's own note that this path is only
 * exercised in a real run). Everything downstream of the scan -- pagination, {@code pk}
 * parsing, cross-segment aggregation, consumed-capacity summation, and the zero/under-count
 * guard rails -- is isolated behind {@link SegmentKeySelector.SegmentScanner} and tested here
 * with a fake, exactly as {@code SizeModelProbeTest} tests {@code SizeModelProbe.runValidation}
 * with a fake {@code BilledCapacitySource}.
 */
class SegmentKeySelectorTest {

    private static String pk(int index) {
        return String.format("K#%08d", index);
    }

    private static int[] intRange(int startInclusive, int endExclusive) {
        int[] a = new int[endExclusive - startInclusive];
        for (int i = 0; i < a.length; i++) a[i] = startInclusive + i;
        return a;
    }

    /**
     * Builds a fake scanner from a fixed segment -> ordered list of pages (each page an array
     * of key indices). The real {@code exclusiveStartKey} passed in by {@link
     * SegmentKeySelector#scanSegment} is ignored in favor of an internal per-segment page
     * cursor: only one thread ever advances a given segment's cursor (the real pagination loop
     * in {@code scanSegment} is a sequential do/while per segment), so this is a faithful
     * stand-in for the real continuation-token protocol without needing to fabricate tokens.
     */
    private static SegmentKeySelector.SegmentScanner fakeScanner(Map<Integer, List<int[]>> pagesBySegment) {
        return fakeScanner(pagesBySegment, seg -> 1.5);
    }

    private static SegmentKeySelector.SegmentScanner fakeScanner(
            Map<Integer, List<int[]>> pagesBySegment, IntToDoubleFunction consumedCapacityPerPage) {
        Map<Integer, AtomicInteger> cursors = new ConcurrentHashMap<>();
        return (segment, total, exclusiveStartKey) -> {
            List<int[]> pages = pagesBySegment.getOrDefault(segment, List.of());
            int pageIdx = cursors.computeIfAbsent(segment, s -> new AtomicInteger(0)).getAndIncrement();
            if (pageIdx >= pages.size()) {
                return new SegmentKeySelector.ScanPage(List.of(), null, 0.0);
            }
            int[] page = pages.get(pageIdx);
            List<String> pks = new ArrayList<>(page.length);
            for (int idx : page) pks.add(pk(idx));
            boolean hasMore = pageIdx + 1 < pages.size();
            Map<String, AttributeValue> lastKey =
                hasMore ? Map.of("pk", AttributeValue.fromS("cursor")) : null;
            return new SegmentKeySelector.ScanPage(pks, lastKey, consumedCapacityPerPage.applyAsDouble(segment));
        };
    }

    @Test
    void aggregatesSingleSegmentSinglePageIntoTheIndexArray() {
        Map<Integer, List<int[]>> pages = Map.of(0, List.of(new int[]{0, 1, 2, 3}));
        SegmentKeySelector.Result r = SegmentKeySelector.runSelection(fakeScanner(pages), 8, 1, 2);

        assertThat(r.indices()).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(r.segmentsUsed()).isEqualTo(1);
        assertThat(r.segmentsTotal()).isEqualTo(2);
        assertThat(r.elapsedNanos()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aggregatesMultipleSegmentsScannedInParallel() {
        Map<Integer, List<int[]>> pages = Map.of(
            0, List.of(new int[]{0, 1}),
            1, List.of(new int[]{2, 3}),
            2, List.of(new int[]{4, 5}));
        SegmentKeySelector.Result r = SegmentKeySelector.runSelection(fakeScanner(pages), 6, 3, 6);

        assertThat(r.indices()).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
    }

    @Test
    void paginatesWithinASegmentUntilLastEvaluatedKeyIsNull() {
        Map<Integer, List<int[]>> pages = Map.of(
            0, List.of(new int[]{0, 1}, new int[]{2, 3}, new int[]{4}));
        SegmentKeySelector.Result r = SegmentKeySelector.runSelection(fakeScanner(pages), 10, 1, 1);

        assertThat(r.indices()).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
    }

    @Test
    void parsesTheKHash08dPkFormatBackToItsIntegerIndex() {
        // itemCount=3 so expectedKeys (3 * 1/1) matches the 3 keys actually returned -- this
        // test is about pk parsing, not the under-count guard (covered separately below).
        Map<Integer, List<int[]>> pages = Map.of(0, List.of(new int[]{0, 42, 2_097_151}));
        SegmentKeySelector.Result r = SegmentKeySelector.runSelection(fakeScanner(pages), 3, 1, 1);

        assertThat(r.indices()).containsExactlyInAnyOrder(0, 42, 2_097_151);
    }

    @Test
    void sumsConsumedCapacityAcrossSegmentsAndPages() {
        Map<Integer, List<int[]>> pages = Map.of(
            0, List.of(new int[]{0}, new int[]{1}),
            1, List.of(new int[]{2}));
        SegmentKeySelector.SegmentScanner scanner = fakeScanner(pages, seg -> 10.0);
        SegmentKeySelector.Result r = SegmentKeySelector.runSelection(scanner, 6, 2, 2);

        // segment 0: two pages x 10.0 = 20.0; segment 1: one page x 10.0 = 10.0 -> 30.0 total.
        assertThat(r.consumedCapacityUnits()).isEqualTo(30.0);
    }

    @Test
    void underCountGuardFiresWhenFarFewerKeysThanExpectedAreReturned() {
        // expected = itemCount * used/total = 1000 * 1/1 = 1000; returning 10 is far under 50%.
        Map<Integer, List<int[]>> pages = Map.of(0, List.of(intRange(0, 10)));
        assertThatThrownBy(() -> SegmentKeySelector.runSelection(fakeScanner(pages), 1000, 1, 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fewer")
            .hasMessageContaining("expected");
    }

    @Test
    void justAboveTheFiftyPercentGuardThresholdPasses() {
        // expected = 100 * 1/1 = 100; 51 keys is just over 50%.
        Map<Integer, List<int[]>> pages = Map.of(0, List.of(intRange(0, 51)));
        SegmentKeySelector.Result r = SegmentKeySelector.runSelection(fakeScanner(pages), 100, 1, 1);
        assertThat(r.indices()).hasSize(51);
    }

    @Test
    void exactlyFiftyPercentIsTheBoundaryAndPasses() {
        // expected = 100; 50 keys is exactly 50%, and the guard is a strict "<" check, so the
        // boundary itself passes -- only strictly-fewer-than-50% aborts.
        Map<Integer, List<int[]>> pages = Map.of(0, List.of(intRange(0, 50)));
        SegmentKeySelector.Result r = SegmentKeySelector.runSelection(fakeScanner(pages), 100, 1, 1);
        assertThat(r.indices()).hasSize(50);
    }

    @Test
    void zeroKeysAbortsWithAClearMessage() {
        Map<Integer, List<int[]>> pages = Map.of(0, List.of(new int[0]));
        assertThatThrownBy(() -> SegmentKeySelector.runSelection(fakeScanner(pages), 1000, 1, 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("zero keys");
    }

    @Test
    void anUnparseablePkThrowsRatherThanSilentlyDroppingTheKey() {
        SegmentKeySelector.SegmentScanner scanner = (segment, total, startKey) ->
            new SegmentKeySelector.ScanPage(List.of("not-a-valid-pk"), null, 1.0);
        assertThatThrownBy(() -> SegmentKeySelector.runSelection(scanner, 10, 1, 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unparseable");
    }

    @Test
    void directParseIndexRoundTripsTheKSharpFormat() {
        assertThat(SegmentKeySelector.parseIndex("K#00000000")).isEqualTo(0);
        assertThat(SegmentKeySelector.parseIndex("K#00000042")).isEqualTo(42);
        assertThat(SegmentKeySelector.parseIndex("K#02097151")).isEqualTo(2_097_151);
    }

    @Test
    void productionShapedSelectionReturnsTheFullExpectedCountWithNoGuardTrip() {
        // Mirrors conf/prod.properties: readSegmentsTotal=40, readSegmentsUsed=15,
        // itemCount=2,097,152 -> expected 786,432 keys, evenly split across 15 segments.
        int itemCount = 2_097_152, used = 15, total = 40;
        int perSegment = itemCount * used / total / used; // 52,428 each, sums to 786,420 (close enough)
        Map<Integer, List<int[]>> pages = new java.util.HashMap<>();
        for (int seg = 0; seg < used; seg++) {
            pages.put(seg, List.of(intRange(seg * perSegment, (seg + 1) * perSegment)));
        }
        SegmentKeySelector.Result r = SegmentKeySelector.runSelection(fakeScanner(pages), itemCount, used, total);
        assertThat(r.indices()).hasSize(perSegment * used);
        assertThat(r.segmentsUsed()).isEqualTo(15);
        assertThat(r.segmentsTotal()).isEqualTo(40);
    }
}
