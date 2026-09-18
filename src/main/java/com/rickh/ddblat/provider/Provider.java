package com.rickh.ddblat.provider;

/**
 * Which DynamoDB-compatible service the harness is measuring, and what that service can tell us
 * about itself.
 *
 * Both are reached with the same AWS SDK v2 client -- Oracle's Autonomous AI Database exposes a
 * wire-compatible endpoint -- so the difference is not the protocol but the telemetry. Several
 * of the harness's validity criteria are built on capacity the service reports back, and Oracle
 * reports none of it (measured 2026-09-11, ADB 26ai: ConsumedCapacity null on PutItem, GetItem
 * strong and eventual, Scan and BatchWriteItem).
 *
 * These flags exist so that a criterion which cannot be evaluated is recorded as
 * NOT APPLICABLE rather than silently passing. A validity gate that quietly weakens itself on a
 * platform it cannot inspect would be worse than no gate: it would certify numbers it never
 * actually checked.
 */
public enum Provider {

    /** Amazon DynamoDB. Reports consumed capacity; CloudWatch cross-check available. */
    AWS(true, true, 400 * 1024, true, true, true),

    /**
     * Oracle Autonomous AI Database API for DynamoDB.
     *
     * Item-size ceiling is deliberately the same 400 KiB the harness models for AWS even though
     * Oracle accepted a 450 KiB item in testing: the point of the comparison is identical
     * payloads, so the stricter of the two limits is the one that binds.
     */
    OCI(false, false, 400 * 1024, false, true, true),

    /**
     * ScyllaDB Alternator — the DynamoDB-compatible API, on ScyllaDB Cloud or self-hosted.
     *
     * Differs from both other providers in a way that reaches the control loop rather than just
     * the client. Alternator has NO provisioned-throughput model: per the compatibility
     * documentation, "The BillingMode and ProvisionedThroughput options on a table need to be
     * valid but are ignored", the service "behaves like DynamoDB's BillingMode=PAY_PER_REQUEST:
     * All requests are accepted without a per-table throughput cap", and "Throttle events do not
     * occur in Alternator because per-table throughput limits are not enforced."
     *
     * Two consequences, both expressed as flags below rather than as special cases in Main:
     * there is no capacity to set, take a fraction of, or reset at teardown; and the zero-throttle
     * validity criterion cannot be earned here, so it must read NOT APPLICABLE rather than pass.
     * The real overload signal is the server-side counter scylla_alternator_requests_shed.
     *
     * On the other side of the ledger it is closer to DynamoDB than Oracle is: SigV4 signing
     * works normally, parallel Scan's Segment/TotalSegments is supported and tested upstream, and
     * eventually-consistent reads are a genuinely distinct operation (LOCAL_ONE against
     * LOCAL_QUORUM for strong), so the R-B phase measures something real.
     */
    SCYLLA(false, false, 400 * 1024, true, false, false);

    private final boolean reportsConsumedCapacity;
    private final boolean hasCloudWatch;
    private final int maxItemBytes;
    private final boolean hasEventuallyConsistentReads;
    private final boolean hasProvisionedCapacity;
    private final boolean enforcesThrottling;

    Provider(boolean reportsConsumedCapacity, boolean hasCloudWatch, int maxItemBytes,
             boolean hasEventuallyConsistentReads, boolean hasProvisionedCapacity,
             boolean enforcesThrottling) {
        this.reportsConsumedCapacity = reportsConsumedCapacity;
        this.hasCloudWatch = hasCloudWatch;
        this.maxItemBytes = maxItemBytes;
        this.hasEventuallyConsistentReads = hasEventuallyConsistentReads;
        this.hasProvisionedCapacity = hasProvisionedCapacity;
        this.enforcesThrottling = enforcesThrottling;
    }

    /**
     * Whether the service has settable provisioned capacity at all.
     *
     * Distinct from {@code Config.manageCapacity()}, which asks whether THIS RUN should manage
     * capacity or leave it to a caller orchestrating a series. This asks whether capacity is a
     * thing on the service in the first place. False means every UpdateTable is pointless: the
     * SWITCH between write and read capacity does nothing, teardown has nothing to lower, and
     * targetFraction has no denominator -- so loadWcu/readRcu become the absolute rate the run
     * intends to drive rather than a fraction of what was bought.
     */
    public boolean hasProvisionedCapacity() { return hasProvisionedCapacity; }

    /**
     * Whether the service throttles a table that exceeds its provisioned rate.
     *
     * False makes the zero-throttle validity criterion unearnable: no request can ever be
     * throttled, so the check passes without testing anything. A gate that always passes is
     * worse than no gate, because it certifies a property it never examined -- so a provider
     * with this false must report that criterion NOT APPLICABLE and rely on a service-side
     * overload signal instead.
     */
    public boolean enforcesThrottling() { return enforcesThrottling; }

    /**
     * Whether an eventually consistent read is a distinct operation on this service.
     *
     * DynamoDB serves eventually consistent reads from any replica at half the capacity cost.
     * Oracle's Autonomous AI Database has no such path: ConsistentRead=false returns the same
     * strongly consistent result over the same code path. Running the phase anyway would
     * measure strong reads a second time and label them "eventual" -- and worse, the capacity
     * model would charge 7.5 units for work that cost 15, crediting the achieved rate with
     * twice the throughput actually delivered.
     *
     * So the phase is SKIPPED on such a provider rather than run and reported.
     */
    public boolean hasEventuallyConsistentReads() { return hasEventuallyConsistentReads; }

    /** False when achieved throughput must be derived from the size model instead. */
    public boolean reportsConsumedCapacity() { return reportsConsumedCapacity; }

    /** False when the client-vs-service capacity cross-check cannot run at all. */
    public boolean hasCloudWatch() { return hasCloudWatch; }

    /** Largest single item the harness will attempt on this provider. */
    public int maxItemBytes() { return maxItemBytes; }

    /**
     * Whether the size-model probe -- which compares predicted capacity against what the service
     * actually billed -- can produce a verdict here. Without reported capacity there is nothing
     * to compare against, so the probe is skipped rather than passed.
     */
    public boolean canVerifySizeModel() { return reportsConsumedCapacity; }


    /**
     * The AWS region name used to build the SigV4 credential scope when signing requests.
     *
     * Oracle's endpoint VALIDATES this and accepts only {@code us-west-2}, regardless of which
     * OCI region the Autonomous Database actually lives in. Measured 2026-09-11 against an ADB
     * in us-ashburn-1: us-west-2 authenticated; us-east-1, us-west-1, eu-west-1, ap-southeast-2
     * and the database's own us-ashburn-1 all returned
     * {@code 401 Invalid credential}. This is undocumented, so it is pinned here with the
     * evidence rather than left as a mystery constant someone later "corrects" to the real
     * region and breaks every OCI run.
     */
    public String signingRegion() {
        return this == OCI ? "us-west-2" : null;   // null: AWS uses the configured region
    }

    public static Provider parse(String s) {
        if (s == null || s.isBlank()) return AWS;
        return switch (s.trim().toLowerCase()) {
            case "aws", "dynamodb"  -> AWS;
            case "oci", "oracle", "adb" -> OCI;
            case "scylla", "scylladb", "alternator" -> SCYLLA;
            default -> throw new IllegalArgumentException(
                "unknown provider '" + s + "': expected aws, oci or scylla");
        };
    }
}
