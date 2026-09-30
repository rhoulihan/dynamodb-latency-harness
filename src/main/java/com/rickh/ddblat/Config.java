package com.rickh.ddblat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import com.rickh.ddblat.provider.Provider;

public record Config(String table, String region, int itemCount, long loadWcu, long presplitWcu,
                     long readRcu, double targetFraction, int readSegmentsTotal, int readSegmentsUsed,
                     Duration windowDuration, long warmNanos,
                     long minRampNanos, int initialThreads, int maxThreads,
                     Path resultsDir, String s3Bucket, String s3Prefix,
                     Path checkpointFile, boolean selfStop, Path harnessLogFile,
                     boolean skipLoad, boolean manageCapacity,
                     Provider provider, String ociDatabaseOcid, Path ociKeyFile,
                     String scyllaEndpoint, Path scyllaKeyFile,
                     int itemSize, boolean batchOps, int batchGetSize, int batchWriteSize,
                     int txnItems, long batchWriteWcu, long txnWcu) {

    public static Config load(Path file) throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) { p.load(in); }

        String table = require(p, "table");
        String region = require(p, "region");
        int itemCount = Integer.parseInt(require(p, "itemCount"));
        if (itemCount <= 0 || Integer.bitCount(itemCount) != 1) {
            throw new IllegalArgumentException(
                "itemCount must be a power of two (the read cycle requires it), got " + itemCount);
        }
        Path resultsDir = Path.of(require(p, "resultsDir"));
        double targetFraction = Double.parseDouble(p.getProperty("targetFraction", "0.90"));
        if (targetFraction <= 0 || targetFraction > 1) {
            throw new IllegalArgumentException("targetFraction must be in (0,1], got " + targetFraction);
        }
        long loadWcu = Long.parseLong(require(p, "loadWcu"));
        // Defaults to loadWcu so existing configs behave exactly as before this key existed.
        // Partition count is fixed forever at table-creation time (DynamoDB partitions never
        // merge), so creating at a higher WCU than the load will actually drive, then dropping
        // to loadWcu, buys more partitions -- and thus more per-partition headroom -- than
        // creating directly at loadWcu. Pre-splitting BELOW loadWcu would create fewer
        // partitions than a plain create at loadWcu and then still have to raise capacity
        // anyway, which is strictly worse than not pre-splitting at all.
        long presplitWcu = Long.parseLong(p.getProperty("presplitWcu", Long.toString(loadWcu)));
        if (presplitWcu < loadWcu) {
            throw new IllegalArgumentException(
                "presplitWcu (" + presplitWcu + ") must be >= loadWcu (" + loadWcu + "): "
                + "pre-splitting below the test level would be worse than not pre-splitting");
        }

        // --- readSegmentsTotal / readSegmentsUsed: segment-scoped read key selection ----------
        // Disabled by default (readSegmentsTotal=0): read phases cycle over the whole key space
        // exactly as before this existed. When enabled (readSegmentsTotal > 0), Main runs a
        // parallel Scan over segments 0..readSegmentsUsed-1 of readSegmentsTotal and reads only
        // that subset -- see SegmentKeySelector's javadoc for why (spreading load over a known
        // fraction of partitions instead of all of them).
        int readSegmentsTotal = Integer.parseInt(p.getProperty("readSegmentsTotal", "0"));
        String readSegmentsUsedRaw = p.getProperty("readSegmentsUsed");
        int readSegmentsUsed = readSegmentsUsedRaw == null ? 0 : Integer.parseInt(readSegmentsUsedRaw.trim());
        if (readSegmentsTotal < 0) {
            throw new IllegalArgumentException(
                "readSegmentsTotal must be >= 0, got " + readSegmentsTotal);
        }
        if (readSegmentsUsed < 0) {
            throw new IllegalArgumentException(
                "readSegmentsUsed must be >= 0, got " + readSegmentsUsed);
        }
        if (readSegmentsTotal == 0) {
            if (readSegmentsUsedRaw != null) {
                throw new IllegalArgumentException(
                    "readSegmentsUsed (" + readSegmentsUsed + ") is set without readSegmentsTotal "
                    + "-- set readSegmentsTotal > 0 to enable segment-scoped reads, or remove "
                    + "readSegmentsUsed to leave the feature disabled");
            }
        } else {
            if (readSegmentsUsedRaw == null) {
                throw new IllegalArgumentException(
                    "readSegmentsTotal=" + readSegmentsTotal + " requires readSegmentsUsed to "
                    + "also be set (0 < readSegmentsUsed < readSegmentsTotal)");
            }
            if (readSegmentsUsed <= 0 || readSegmentsUsed >= readSegmentsTotal) {
                throw new IllegalArgumentException(
                    "readSegmentsUsed (" + readSegmentsUsed + ") must satisfy 0 < readSegmentsUsed "
                    + "< readSegmentsTotal (" + readSegmentsTotal + ")");
            }
        }

        // --- skipLoad ------------------------------------------------------------------------
        // Read-only re-run against a table an earlier run already loaded. The dataset is 118 GiB
        // and takes ~76 minutes of write capacity to lay down; re-running the read phases against
        // it should not pay that again. When true, Main skips table creation, the size-model
        // probe (100 PutItems, which would throttle hard against a post-run table sitting at
        // WCU 10) and the LOAD phase itself, and starts at SWITCH.
        boolean skipLoad = Boolean.parseBoolean(p.getProperty("skipLoad", "false"));
        if (skipLoad && p.getProperty("presplitWcu") != null) {
            throw new IllegalArgumentException(
                "skipLoad=true cannot be combined with presplitWcu (" + presplitWcu + "): "
                + "pre-splitting buys partitions at table-CREATION time and a skipLoad run "
                + "never creates the table, so the partition count is whatever the loading run "
                + "already established. Remove presplitWcu to make that explicit.");
        }

        // --- manageCapacity --------------------------------------------------------------------
        // DynamoDB allows 4 provisioned-throughput DECREASES per table per UTC day and then one
        // per hour. A run that owns its capacity spends one decrease at TEARDOWN, so a five-run
        // series spends five and stalls an hour between the later ones -- and a decrease refused
        // mid-series strands the table at full read capacity (~$5.20/hr at 40,000 RCU), which is
        // exactly what happened on 2026-09-10. manageCapacity=false hands capacity to the caller:
        // raise once before the series, drop once after, one decrease total.
        //
        // The harness still VERIFIES capacity at SWITCH rather than trusting it. Not managing is
        // not the same as not looking: driving 36,000 RCU/s at a table still at 10 RCU throttles
        // from the first request and fills the window with retry latencies.
        boolean manageCapacity = Boolean.parseBoolean(p.getProperty("manageCapacity", "true"));
        if (!manageCapacity && !skipLoad) {
            throw new IllegalArgumentException(
                "manageCapacity=false requires skipLoad=true: a load phase against a table "
                + "nobody raised to loadWcu (" + loadWcu + ") would drive writes at capacity "
                + "that does not exist and throttle from the first request. manageCapacity=false "
                + "is for repeated read-only runs, where the caller raises capacity once for the "
                + "whole series.");
        }

        // --- provider ---------------------------------------------------------------------
        // Defaults to AWS so every pre-existing config file behaves exactly as before. OCI means
        // Oracle's Autonomous AI Database API for DynamoDB, reached with the same AWS SDK client
        // pointed at a different endpoint -- see Provider for what that service can and cannot
        // report back about itself.
        Provider provider = Provider.parse(p.getProperty("provider"));
        String ociDatabaseOcid = p.getProperty("ociDatabaseOcid");
        String ociKeyFileRaw = p.getProperty("ociKeyFile");
        if (provider == Provider.OCI) {
            if (ociDatabaseOcid == null || ociDatabaseOcid.isBlank()) {
                throw new IllegalArgumentException(
                    "provider=oci requires ociDatabaseOcid (the Autonomous Database OCID); the "
                    + "key-value store endpoint is built from it and the region");
            }
            if (ociKeyFileRaw == null || ociKeyFileRaw.isBlank()) {
                throw new IllegalArgumentException(
                    "provider=oci requires ociKeyFile: the JSON returned by "
                    + "POST /adb/auth/v1/databases/{ocid}/accesskeys");
            }
        }
        Path ociKeyFile = ociKeyFileRaw == null ? null : Path.of(ociKeyFileRaw);

        // --- scylla ----------------------------------------------------------------------------
        // ScyllaDB Alternator is reached by full endpoint URL rather than assembled from a
        // region and an id: on Bring-Your-Own-Account the cluster is a set of nodes in a peered
        // VPC, so the address is whatever ScyllaDB Cloud allocated and there is nothing to derive.
        String scyllaEndpoint = p.getProperty("scyllaEndpoint");
        String scyllaKeyFileRaw = p.getProperty("scyllaKeyFile");
        if (provider == Provider.SCYLLA) {
            if (scyllaEndpoint == null || scyllaEndpoint.isBlank()) {
                throw new IllegalArgumentException(
                    "provider=scylla requires scyllaEndpoint, the full Alternator URL "
                    + "(e.g. https://node-0.example.scylladb.com:8043)");
            }
            // Fail here rather than at the first request: a schemeless endpoint is the likely
            // paste error and the SDK's own failure does not name the cause.
            com.rickh.ddblat.provider.ScyllaCredentials.validateEndpoint(scyllaEndpoint);
            if (scyllaKeyFileRaw == null || scyllaKeyFileRaw.isBlank()) {
                throw new IllegalArgumentException(
                    "provider=scylla requires scyllaKeyFile: a JSON file holding the role name as "
                    + "access_key_id and its salted_hash as secret_access_key");
            }
        }
        Path scyllaKeyFile = scyllaKeyFileRaw == null ? null : Path.of(scyllaKeyFileRaw);

        // --- itemSize ----------------------------------------------------------------------------
        // Every item is exactly this many bytes. Defaults to the original 59 KiB so existing
        // configs are unchanged. The MELI PoC runs the same harness at 370, 523, 2,000, 10,000
        // and 50,000 bytes; the template builder and every workload's capacity accounting follow.
        int itemSize = com.rickh.ddblat.model.ItemSizeModel.validateItemSize(Integer.parseInt(
            p.getProperty("itemSize", Integer.toString(com.rickh.ddblat.model.ItemSizeModel.ITEM_SIZE)).trim()));

        // --- batch operations --------------------------------------------------------------------
        // After the single-item read phases: BatchGetItem (strong and eventual), a full
        // BatchWriteItem, and TransactWriteItems. Off by default. The write phases overwrite
        // existing keys at the same size, so the dataset is unchanged by them.
        boolean batchOps = Boolean.parseBoolean(p.getProperty("batchOps", "false"));
        int batchGetSize = Integer.parseInt(p.getProperty("batchGetSize", "100").trim());
        int batchWriteSize = Integer.parseInt(p.getProperty("batchWriteSize", "25").trim());
        int txnItems = Integer.parseInt(p.getProperty("txnItems", "100").trim());
        if (batchGetSize < 1 || batchGetSize > 100) {
            throw new IllegalArgumentException("batchGetSize must be in [1,100], got " + batchGetSize);
        }
        if (batchWriteSize < 1 || batchWriteSize > 25) {
            throw new IllegalArgumentException("batchWriteSize must be in [1,25], got " + batchWriteSize);
        }
        if (txnItems < 1 || txnItems > 100) {
            throw new IllegalArgumentException("txnItems must be in [1,100], got " + txnItems);
        }
        // Pacing ceilings for the two batch-write phases, in WCU. Separate because at fixed call
        // rates they differ by an order of magnitude: a 25-item BatchWriteItem of 50 KB items is
        // 1,225 WCU, an 83-item transaction of the same items is 8,134. The table holds the
        // larger of the two from SWITCH onward -- see postLoadWcu().
        long batchWriteWcu = Long.parseLong(p.getProperty("batchWriteWcu", "0").trim());
        long txnWcu = Long.parseLong(p.getProperty("txnWcu", "0").trim());
        if (batchOps && (batchWriteWcu <= 0 || txnWcu <= 0)) {
            throw new IllegalArgumentException("batchOps=true requires batchWriteWcu > 0 and "
                + "txnWcu > 0: the BatchWriteItem and TransactWriteItems phases are paced against them");
        }

        return new Config(
            table, region, itemCount,
            loadWcu, presplitWcu,
            Long.parseLong(require(p, "readRcu")),
            targetFraction, readSegmentsTotal, readSegmentsUsed,
            Duration.ofMinutes(Long.parseLong(p.getProperty("windowMinutes", "20"))),
            Long.parseLong(p.getProperty("warmSeconds", "60")) * 1_000_000_000L,
            Long.parseLong(p.getProperty("minRampMinutes", "5")) * 60L * 1_000_000_000L,
            Integer.parseInt(p.getProperty("initialThreads", "32")),
            Integer.parseInt(p.getProperty("maxThreads", "256")),
            resultsDir,
            p.getProperty("s3Bucket", ""),
            p.getProperty("s3Prefix", "results"),
            // Deliberately defaults OUTSIDE resultsDir: wiping results must not destroy
            // resume state, or a restart silently reloads from zero.
            Path.of(p.getProperty("checkpointFile",
                                  resultsDir.resolveSibling("load.checkpoint").toString())),
            Boolean.parseBoolean(p.getProperty("selfStop", "false")),
            // scripts/30-run.sh redirects the harness's own stdout/stderr here; it is the
            // phase-transition and violation record, not something the JVM writes itself, so
            // Main can only pick it up and copy it into resultsDir for upload. Configurable
            // (rather than hardcoded) because the default is instance-filesystem-specific and
            // every test needs a harmless value that will simply never exist.
            Path.of(p.getProperty("harnessLogFile", "/var/log/ddblat.log")),
            skipLoad, manageCapacity,
            provider, ociDatabaseOcid, ociKeyFile,
            scyllaEndpoint, scyllaKeyFile,
            itemSize, batchOps, batchGetSize, batchWriteSize, txnItems, batchWriteWcu, txnWcu);
    }

    /** WCU the table holds from SWITCH onward: enough for batch writes when they run, else 10. */
    public long postLoadWcu() {
        return batchOps ? Math.max(batchWriteWcu, txnWcu) : 10;
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required config key: " + key);
        }
        return v.trim();
    }

    /** What a run should do about provisioned capacity. See {@link #capacityMode()}. */
    public enum CapacityMode {
        /** Issue the UpdateTable calls that move capacity to the configured level. */
        SET,
        /** Do not set it, but assert it is already right before driving load against it. */
        VERIFY,
        /** The service has no provisioned capacity; touching or checking it is meaningless. */
        SKIP
    }

    /**
     * The single statement of when a run sets capacity, verifies it, or ignores it.
     *
     * Two independent inputs, asked at two call sites (SWITCH and TEARDOWN), which is exactly the
     * shape that has produced bugs in this codebase before -- so it is stated once here and both
     * sites ask it rather than carrying their own copy.
     *
     * SKIP is not "VERIFY, but lazier". On a provider without provisioned capacity, verification
     * is actively misleading: DescribeTable echoes back whatever ProvisionedThroughput was named
     * at create time, so a check would compare a configured number against itself and pass while
     * testing nothing about the service.
     */
    public CapacityMode capacityMode() {
        if (!provider.hasProvisionedCapacity()) return CapacityMode.SKIP;
        return manageCapacity ? CapacityMode.SET : CapacityMode.VERIFY;
    }
}
