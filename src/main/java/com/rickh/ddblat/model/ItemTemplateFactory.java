package com.rickh.ddblat.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;  // SplittableRandom implements it

/**
 * Pre-builds one immutable attribute map per size template. Item generation on the
 * request path then costs a 20-entry map holding 19 shared value references plus the
 * key -- roughly 1 KB instead of the ~50 KB a freshly generated payload would cost.
 *
 * Reuse is not observable server-side: DynamoDB neither compresses nor deduplicates,
 * so item size, wire bytes, and stored bytes are identical to generating fresh content.
 */
public final class ItemTemplateFactory {

    private static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final int BLOB_COUNT = 5;

    private final Map<String, AttributeValue>[] templates;

    @SuppressWarnings("unchecked")
    public ItemTemplateFactory(long seed) {
        this.templates = new Map[ItemSizeModel.TEMPLATE_COUNT];
        RandomGenerator rng = new java.util.SplittableRandom(seed);
        for (int j = 0; j < templates.length; j++) {
            templates[j] = buildTemplate(j, rng);
        }
    }

    /** The 19 non-key attributes for template j. Same instance on every call. */
    public Map<String, AttributeValue> template(int j) {
        return templates[j];
    }

    /** A full 20-attribute item: 19 shared references plus this item's key. */
    public Map<String, AttributeValue> itemFor(String key, int j) {
        Map<String, AttributeValue> item = new HashMap<>(32);
        item.putAll(templates[j]);
        item.put("pk", AttributeValue.fromS(key));
        return item;
    }

    private Map<String, AttributeValue> buildTemplate(int j, RandomGenerator rng) {
        int payload = ItemSizeModel.blobPayloadBytes(ItemSizeModel.sizeForTemplate(j));
        Map<String, AttributeValue> m = new LinkedHashMap<>(32);

        m.put("ver",      AttributeValue.fromN("1"));
        m.put("created",  AttributeValue.fromN(digits(rng, 13)));
        m.put("updated",  AttributeValue.fromN(digits(rng, 13)));
        m.put("active",   AttributeValue.fromBool(true));
        m.put("region",   AttributeValue.fromS("us-east-1"));
        m.put("tenant",   AttributeValue.fromS(text(rng, 16)));
        m.put("category", AttributeValue.fromS(text(rng, 12)));
        m.put("status",   AttributeValue.fromS(text(rng, 8)));
        m.put("seq",      AttributeValue.fromN(digits(rng, 7)));
        m.put("score",    AttributeValue.fromN(digits(rng, 6)));
        m.put("weight",   AttributeValue.fromN(digits(rng, 6)));
        m.put("flags",    AttributeValue.fromN(digits(rng, 4)));
        m.put("shard",    AttributeValue.fromN(digits(rng, 3)));
        m.put("label",    AttributeValue.fromS(text(rng, 24)));

        int base = payload / BLOB_COUNT;
        int remainder = payload - base * BLOB_COUNT;
        for (int b = 0; b < BLOB_COUNT; b++) {
            int len = base + (b == BLOB_COUNT - 1 ? remainder : 0);
            m.put("blob" + b, AttributeValue.fromS(text(rng, len)));
        }
        return Map.copyOf(m);
    }

    /** Alphanumeric only: quote and backslash would JSON-escape and inflate wire bytes. */
    private static String text(RandomGenerator rng, int len) {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = ALPHABET.charAt(rng.nextInt(ALPHABET.length()));
        return new String(c);
    }

    /** Exactly `len` digits, never leading zero, so the size model's digit widths hold. */
    private static String digits(RandomGenerator rng, int len) {
        char[] c = new char[len];
        c[0] = (char) ('1' + rng.nextInt(9));
        for (int i = 1; i < len; i++) c[i] = (char) ('0' + rng.nextInt(10));
        return new String(c);
    }

    /**
     * DynamoDB item size: sum over attributes of UTF-8 name bytes plus value bytes.
     * S is its UTF-8 length, BOOL is 1, N is ceil(significantDigits / 2) + 1.
     */
    public static int computeItemSizeBytes(Map<String, AttributeValue> item) {
        int total = 0;
        for (Map.Entry<String, AttributeValue> e : item.entrySet()) {
            total += e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            AttributeValue v = e.getValue();
            if (v.s() != null) {
                total += v.s().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            } else if (v.n() != null) {
                int sig = 0;
                for (char c : v.n().toCharArray()) if (c >= '0' && c <= '9') sig++;
                total += (sig + 1) / 2 + 1;
            } else if (v.bool() != null) {
                total += 1;
            } else {
                throw new IllegalArgumentException("unsupported attribute type for " + e.getKey());
            }
        }
        return total;
    }
}
