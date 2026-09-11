package com.rickh.ddblat.model;

/**
 * Dense key space. All key strings are materialized once at startup so that key
 * lookup on the request path is an array index with zero allocation and no
 * String.format. For the full 2^21 space this retains roughly 130 MB, which is
 * a deliberate trade against a 24 GiB heap.
 *
 * cycleIndex is a full-period LCG over the space: because size is a power of two
 * and P is odd, i -> (i * P) mod size visits every index exactly once per cycle.
 * That removes repeated-key locality as a confound in the read phases.
 */
public final class KeySpace {

    /** Odd, so gcd(P, 2^n) == 1 and the cycle has full period. */
    public static final int P = 0x1F1F1F;

    private final String[] keys;
    private final int mask;

    public KeySpace(int itemCount) {
        if (itemCount <= 0 || Integer.bitCount(itemCount) != 1) {
            throw new IllegalArgumentException("itemCount must be a power of two, got " + itemCount);
        }
        this.mask = itemCount - 1;
        this.keys = new String[itemCount];
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < itemCount; i++) {
            sb.setLength(0);
            sb.append("K#");
            String d = Integer.toString(i);
            for (int p = d.length(); p < 8; p++) sb.append('0');
            sb.append(d);
            keys[i] = sb.toString();
        }
    }

    public int size() {
        return keys.length;
    }

    public String keyAt(int index) {
        return keys[index];
    }

    public int cycleIndex(long i) {
        return (int) ((i * P) & mask);
    }
}
