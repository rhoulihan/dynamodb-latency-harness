package com.rickh.ddblat.worker;

public interface Workload {

    /**
     * 0 OK, 1 throttled, 2 other 4xx, 3 5xx, 4 timeout or IO.
     *
     * {@code itemFound} is meaningful only for a successful (statusClass == 0) read: did the
     * response actually contain an item, or was it a miss? See task-17 -- a DynamoDB miss
     * consumes roughly 1 RCU where a hit on a 50 KiB item costs roughly 13, so a read phase
     * running against absent data collapses the achieved-CU/s signal the ramp depends on
     * without ever throwing an error, and its latency numbers are not the latency of a real
     * item read. Writes and error outcomes have no hit/miss concept at all; the 5-arg
     * constructor below defaults {@code itemFound} to {@code true} ("no evidence of a miss")
     * for exactly those cases, so every existing call site is unaffected.
     */
    record Outcome(long latencyNanos, double consumedCu, int attempts, int statusClass,
                   boolean throttled, boolean itemFound) {

        public Outcome(long latencyNanos, double consumedCu, int attempts, int statusClass,
                       boolean throttled) {
            this(latencyNanos, consumedCu, attempts, statusClass, throttled, true);
        }
    }

    /** Executes one request. Never throws for throttles -- they are an outcome, not an error. */
    Outcome execute(int index);

    /** Charged to the token bucket before the call; real cost is only known afterward. */
    double estimatedCapacityUnits(int index);

    /**
     * The largest value {@link #estimatedCapacityUnits} can ever return for this workload,
     * e.g. the max-size item's cost from the size model. Callers use this to floor the
     * token bucket's capacity (see {@code TokenBucket(rate, burstSeconds, minCapacityUnits)})
     * so a single request can never exceed a bucket it is paced against -- which would make
     * {@code TokenBucket.acquire} unsatisfiable forever.
     */
    double maxCapacityUnits();

    byte phaseId();
}
