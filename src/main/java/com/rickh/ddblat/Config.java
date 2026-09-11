package com.rickh.ddblat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record Config(String table, String region, int itemCount, long loadWcu, long presplitWcu,
                     long readRcu, double targetFraction, int readSegmentsTotal, int readSegmentsUsed,
                     Duration windowDuration, long warmNanos,
                     long minRampNanos, int initialThreads, int maxThreads,
                     Path resultsDir, String s3Bucket, String s3Prefix,
                     Path checkpointFile, boolean selfStop, Path harnessLogFile,
                     boolean skipLoad, boolean manageCapacity) {

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
            skipLoad, manageCapacity);
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required config key: " + key);
        }
        return v.trim();
    }
}
