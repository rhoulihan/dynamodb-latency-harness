package com.rickh.ddblat.model;

/**
 * Deterministic item-size model. Every item is exactly ITEM_SIZE bytes, so the dataset
 * total (ITEM_COUNT * ITEM_SIZE) is exact by construction -- no distribution to sum.
 *
 * Template selection still uses a fixed permutation of the low 8 key bits rather than a
 * hash: over the dense key space 0..ITEM_COUNT-1 this guarantees each template is used
 * exactly ITEM_COUNT/TEMPLATE_COUNT times. That no longer matters for the dataset total
 * (every template is the same size), but it still matters for what the 256 templates are
 * for: pre-built content reused across items so payload generation stays off the request
 * path (see ItemTemplateFactory). The templates vary content only now, not size.
 */
public final class ItemSizeModel {

    public static final int  ITEM_COUNT     = 1 << 21;              // 2_097_152
    public static final int  TEMPLATE_COUNT = 256;
    public static final int  ITEM_SIZE      = 60_416;               // 59 KiB, every item
    public static final int  FIXED_OVERHEAD = 222;                  // 15 scalars + 5 blob names
    public static final long DATASET_BYTES  = 126_701_535_232L;     // exactly 118 GiB

    private static final int[] PERM = buildPermutation();

    private ItemSizeModel() {}

    /** SplitMix64 finalizer. Used only to seed the template permutation. */
    public static long mix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int[] buildPermutation() {
        Integer[] boxed = new Integer[TEMPLATE_COUNT];
        for (int i = 0; i < TEMPLATE_COUNT; i++) boxed[i] = i;
        java.util.Arrays.sort(boxed, java.util.Comparator.comparingLong(i -> mix64(i)));
        int[] p = new int[TEMPLATE_COUNT];
        for (int i = 0; i < TEMPLATE_COUNT; i++) p[i] = boxed[i];
        return p;
    }

    public static int templateIndex(long key) {
        return PERM[(int) (key & 0xFF)];
    }

    /** Every template is the same size now; j is unused except to select content elsewhere. */
    public static int sizeForTemplate(int j) {
        return ITEM_SIZE;
    }

    public static int sizeForKey(long key) {
        return sizeForTemplate(templateIndex(key));
    }

    public static int blobPayloadBytes(int sizeBytes) {
        return sizeBytes - FIXED_OVERHEAD;
    }

    public static int writeCapacityUnits(int sizeBytes) {
        return ceilDiv(sizeBytes, 1024);
    }

    public static int readCapacityUnitsStrong(int sizeBytes) {
        return ceilDiv(sizeBytes, 4096);
    }

    public static double readCapacityUnitsEventual(int sizeBytes) {
        return readCapacityUnitsStrong(sizeBytes) / 2.0;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
