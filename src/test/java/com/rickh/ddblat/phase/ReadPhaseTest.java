package com.rickh.ddblat.phase;

import com.rickh.ddblat.worker.IndexSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import static org.assertj.core.api.Assertions.*;

/**
 * {@link ReadPhase#cycleSource(int[])} generalizes {@link ReadPhase#cycleSource(com.rickh.ddblat.model.KeySpace)}'s
 * full-period LCG to an arbitrary-length subset (see {@link SegmentKeySelector}), where the
 * power-of-two-only {@code & mask} trick does not apply. These tests prove full-period coverage
 * -- every element visited exactly once per cycle, then repeated identically -- at several
 * awkward lengths: 1, 2, 3 (tiny), 7 and 999_983 (primes), 100 (small factors 2 and 5), and
 * 786_432 (this project's real readSegmentsUsed=15/readSegmentsTotal=40 selection size, factors
 * 2^18 x 3).
 */
class ReadPhaseTest {

    private static int[] identitySubset(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        return a;
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 2, 3, 7, 100, 786_432, 999_983})
    void subsetCycleVisitsEveryElementExactlyOncePerCycle(int n) {
        int[] subset = identitySubset(n);
        IndexSource source = ReadPhase.cycleSource(subset);

        BitSet seen = new BitSet(n);
        for (int i = 0; i < n; i++) {
            int idx = source.next();
            assertThat(idx).as("index at position %d", i).isBetween(0, n - 1);
            assertThat(seen.get(idx)).as("index %d revisited within first cycle at i=%d", idx, i).isFalse();
            seen.set(idx);
        }
        assertThat(seen.cardinality()).isEqualTo(n);
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 2, 3, 7, 100, 786_432, 999_983})
    void subsetCycleRepeatsIdenticallyOnTheSecondPass(int n) {
        int[] subset = identitySubset(n);
        IndexSource source = ReadPhase.cycleSource(subset);

        int[] firstPass = new int[n];
        for (int i = 0; i < n; i++) firstPass[i] = source.next();
        for (int i = 0; i < n; i++) {
            assertThat(source.next()).as("position %d on second pass", i).isEqualTo(firstPass[i]);
        }
    }

    @Test
    void cycleWorksOverAnArbitraryNonContiguousSubsetNotJustAnIdentityRange() {
        // A realistic SegmentKeySelector-shaped subset: sparse, unordered, arbitrary values --
        // order does not matter (the read cycle randomizes anyway), only that every SLOT is
        // visited exactly once, which is what makes every distinct value visited exactly once.
        int[] subset = {42, 7, 1_999_999, 0, 555_555, 3, 17};
        IndexSource source = ReadPhase.cycleSource(subset);

        BitSet seenSlots = new BitSet(subset.length);
        java.util.Set<Integer> seenValues = new java.util.HashSet<>();
        for (int i = 0; i < subset.length; i++) {
            int v = source.next();
            assertThat(seenValues.add(v)).as("value %d revisited at i=%d", v, i).isTrue();
        }
        assertThat(seenValues).containsExactlyInAnyOrder(42, 7, 1_999_999, 0, 555_555, 3, 17);
    }

    @Test
    void rejectsAnEmptySubset() {
        assertThatThrownBy(() -> ReadPhase.cycleSource(new int[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-empty");
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {2, 3, 4, 6, 7, 9, 12, 100, 786_432, 999_983})
    void coprimeMultiplierIsAlwaysCoprimeWithTheLength(int n) {
        long m = ReadPhase.coprimeMultiplier(n);
        assertThat(gcd(m, n)).as("gcd(%d, %d)", m, n).isEqualTo(1);
    }

    private static long gcd(long a, long b) {
        while (b != 0) { long t = b; b = a % b; a = t; }
        return a;
    }
}
