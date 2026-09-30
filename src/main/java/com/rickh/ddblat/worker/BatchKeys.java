package com.rickh.ddblat.worker;

import com.rickh.ddblat.model.KeySpace;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands out disjoint blocks of {@code batchSize} consecutive keys, round-robin over the key space.
 *
 * Batch calls cannot take the per-request index the read cycle supplies and simply read its
 * neighbours: two concurrent TransactWriteItems whose key ranges overlap cancel each other with
 * TransactionConflict, and BatchGetItem / BatchWriteItem reject a duplicate key inside one call.
 * A shared counter makes every in-flight block distinct as long as fewer than
 * {@code itemCount / batchSize} calls are in flight -- 20,971 at the default 2^21 keys and a
 * batch of 100, against a worker pool that tops out in the hundreds.
 *
 * Consecutive key strings still spread across partitions: DynamoDB hashes the key, so K#00000100
 * and K#00000101 land on unrelated partitions.
 */
final class BatchKeys {

    private final KeySpace keys;
    private final int batchSize;
    private final long blocks;
    private final AtomicLong next = new AtomicLong();

    BatchKeys(KeySpace keys, int batchSize) {
        if (batchSize < 1 || batchSize > keys.size()) {
            throw new IllegalArgumentException("batchSize must be in [1, " + keys.size()
                + "], got " + batchSize);
        }
        this.keys = keys;
        this.batchSize = batchSize;
        this.blocks = keys.size() / batchSize;
    }

    int batchSize() { return batchSize; }

    /** Key index of the first key in the next block; the block is [start, start + batchSize). */
    int nextBlockStart() {
        return (int) ((next.getAndIncrement() % blocks) * batchSize);
    }

    String keyAt(int index) { return keys.keyAt(index); }
}
