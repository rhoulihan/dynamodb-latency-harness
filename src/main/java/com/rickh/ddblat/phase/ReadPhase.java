package com.rickh.ddblat.phase;

import com.rickh.ddblat.model.KeySpace;
import com.rickh.ddblat.worker.IndexSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Full-period cycle over the key space: every key is read exactly once per cycle, then the
 * cycle repeats. Removes repeated-key locality as a confound in the tail.
 */
public final class ReadPhase {

    /**
     * Fibonacci-hashing constant (the golden ratio's fractional part, {@code 1/phi}): a
     * standard, well-distributed starting point for a multiplicative step search. Any value
     * would do as a *starting* candidate -- {@link #coprimeMultiplier} always finds a coprime
     * multiplier regardless -- this one just spreads consecutive cycle positions well instead
     * of clustering, same intent as {@code KeySpace.P}.
     */
    private static final double GOLDEN_RATIO_CONJUGATE = 0.6180339887498949;

    private ReadPhase() {}

    public static IndexSource cycleSource(KeySpace keys) {
        AtomicLong i = new AtomicLong();
        return () -> keys.cycleIndex(i.getAndIncrement());
    }

    /**
     * Full-period cycle over an arbitrary-length subset of key indices (see {@link
     * SegmentKeySelector}): every element of {@code subset} is visited exactly once per cycle,
     * then the cycle repeats -- same no-repeat-locality property as {@link #cycleSource(KeySpace)},
     * but {@code subset.length} need not be a power of two, so that method's {@code & mask}
     * trick does not apply here. Instead this uses {@code slot = (i * M) mod N}, which is a
     * bijection on {@code 0 .. N-1} for ANY {@code N} as long as {@code gcd(M, N) == 1} --
     * multiplication by a unit is an automorphism of the additive group {@code Z_N} -- so it
     * generalizes {@link KeySpace}'s power-of-two-only LCG to an arbitrary length. See {@link
     * #coprimeMultiplier} for how {@code M} is chosen, and {@code ReadPhaseTest} for proof of
     * full-period coverage at several awkward lengths (primes, and lengths with small factors).
     */
    public static IndexSource cycleSource(int[] subset) {
        int n = subset.length;
        if (n <= 0) {
            throw new IllegalArgumentException("cycleSource requires a non-empty subset, got length " + n);
        }
        long multiplier = coprimeMultiplier(n);
        AtomicLong i = new AtomicLong();
        return () -> {
            long k = i.getAndIncrement() % n;
            int slot = (int) ((k * multiplier) % n);
            return subset[slot];
        };
    }

    /**
     * Finds a multiplier {@code M} with {@code gcd(M, n) == 1}, starting from a Fibonacci-hash
     * candidate and searching upward (wrapping past {@code n} back to 1) until one is found.
     * This always terminates for {@code n > 1}: {@code gcd(1, n) == 1} for every {@code n}, so
     * the search reaches a valid answer in at most {@code n - 1} steps even in the degenerate
     * case where the Fibonacci-hash starting point happens to share a large factor with {@code
     * n} (e.g. n a multiple of both 2 and 3).
     */
    static long coprimeMultiplier(int n) {
        if (n <= 1) return 1;
        long base = Math.floorMod(Math.round(n * GOLDEN_RATIO_CONJUGATE), (long) n);
        if (base == 0) base = 1;
        long candidate = base;
        for (int tries = 0; tries < n; tries++) {
            if (gcd(candidate, n) == 1) return candidate;
            candidate++;
            if (candidate >= n) candidate = 1;
        }
        // Unreachable for n > 1: gcd(1, n) == 1 always holds, so the loop above always returns
        // no later than the iteration where candidate wraps back to 1.
        throw new IllegalStateException("no coprime multiplier found for n=" + n);
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
