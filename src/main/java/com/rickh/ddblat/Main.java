package com.rickh.ddblat;

import com.rickh.ddblat.aws.*;
import com.rickh.ddblat.metrics.*;
import com.rickh.ddblat.model.*;
import com.rickh.ddblat.phase.*;
import com.rickh.ddblat.rate.*;
import com.rickh.ddblat.record.RecordWriter;
import com.rickh.ddblat.report.JfrDump;
import com.rickh.ddblat.report.SummaryWriter;
import com.rickh.ddblat.worker.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Drives all four phases (LOAD, SWITCH, R-A, R-B, TEARDOWN) including the UpdateTable calls
 * that switch the table's provisioned capacity between them, because this process knows
 * when the load actually finished and a shell script does not.
 */
public final class Main {

    private static final byte LOAD_RAMP = 0, LOAD_WIN = 1,
                              RA_RAMP   = 2, RA_WIN   = 3,
                              RB_RAMP   = 4, RB_WIN   = 5;

    /** The run succeeded and results are complete on local disk, but the S3 upload failed. */
    private static final int S3_UPLOAD_FAILED_EXIT_CODE = 3;

    /**
     * How many max-size requests the bucket's capacity floor must hold, not just one.
     *
     * A real smoke run measured this at 1 (the prior fix: {@code minCapacityUnits =
     * maxRequestCost}) and got 845 RCU/min against a 100 RCU/s floor target -- ~14% of
     * nominal, with 8 workers parked in {@link com.rickh.ddblat.rate.TokenBucket#acquire}.
     * TokenBucketThroughputTest measured the fix empirically rather than reasoning about it
     * (the second time this exact reasoning was tried and got the sizing wrong): a bare,
     * maximally-contended acquire loop turns out to sustain ~95-100% of nominal even at
     * factor 1 on this hardware, at every (rate, cost, thread-count) combination tried,
     * including this exact 100 CU/s x 13 CU x 8-thread configuration -- so the live 14%
     * collapse was not reproducible from TokenBucket contention alone in isolation, and the
     * measurements do not show factor 1 actually failing. It is raised anyway, to a small
     * fixed multiple rather than 1, as a cheap, low-regret margin against exactly the kind of
     * real-infrastructure hiccup (a delayed refiller tick, a GC pause, EC2 CPU-credit
     * throttling) that a fast synthetic JVM test cannot reproduce, but which leaves a
     * one-request bucket with zero slack to absorb: with capacity == cost, ANY missed refill
     * tick makes the very next request wait a full cycle with nothing already banked.
     *
     * 4 was chosen, not a larger number, because burst cost scales with it: at production
     * scale (36,000 CU/s targets, 4,000 CU/s ramp floor) the existing {@code rate *
     * burstSeconds} term is 400-3,600 CU and already dominates {@code maxRequestCost * 4}
     * (which is 26-200 CU) for every workload in this project, so production's burst window
     * is completely unchanged at 0.1s. Only the low-rate smoke floor (100 CU/s) is actually
     * floored by this term, and there the worst case -- a 52 CU max item -- raises capacity
     * to 208 CU, i.e. a 2.08s burst window instead of 0.52s. That is still a small fraction of
     * a smoke table's provisioned throughput and well short of anything that would trip
     * DynamoDB's burst/throttling behavior.
     */
    public static final double CAPACITY_FLOOR_REQUESTS = 4.0;

    public static void main(String[] args) throws Exception {
        String configPath = null;
        if (args.length == 1) {
            configPath = args[0];
        } else if (args.length == 2 && args[0].equals("--config")) {
            configPath = args[1];
        }
        if (configPath == null) {
            System.err.println("usage: ddblat <config.properties>");
            System.err.println("   or: ddblat --config <config.properties>");
            System.exit(2);
        }
        Config cfg = Config.load(Path.of(configPath));
        // Must run before anything below writes into resultsDir, and before createDirectories:
        // an instance reused across runs (or a local rerun against the same resultsDir) can
        // still hold a prior run's DONE, summary.json, and .hlog files. Left in place, they'd
        // be shipped by ResultsUpload alongside this run's artifacts, and a leftover DONE could
        // convince a collector that a run which actually failed partway through had succeeded.
        ResultsDirCleaner.clean(cfg.resultsDir());
        Files.createDirectories(cfg.resultsDir());
        Files.createDirectories(cfg.checkpointFile().toAbsolutePath().getParent());

        long runStart = System.nanoTime();
        var cpu = cpuSupplier();
        KeySpace keys = new KeySpace(cfg.itemCount());
        ItemTemplateFactory factory = new ItemTemplateFactory(20260908L);
        ClientSettings settings = ClientSettings.forThreads(cfg.maxThreads());
        DynamoDbClient ddb = DynamoClientFactory.create(Region.of(cfg.region()), settings, null);
        TableAdmin admin = new TableAdmin(ddb, cfg.table());
        RecordWriter writer = new RecordWriter(
            cfg.resultsDir().resolve("requests.bin.gz"), cfg.maxThreads() * 8, 2_730);
        writer.start();

        List<SummaryWriter.PhaseSummary> summaries = new ArrayList<>();

        // The whole phase sequence runs inside this try so that ANY failure -- a timeout in
        // TableAdmin.awaitActiveWithCapacity, SizeModelProbe.validate aborting (the very gate
        // we want firing), any AWS SDK error mid-phase -- still reaches the finally below and
        // drops the table back to a cheap, harmless capacity instead of leaving it at 40,000
        // WCU (~$26/hr) or 40,000 RCU (~$5/hr) indefinitely.
        boolean success = false;
        try {
            // ---- LOAD ----
            // Table is created at RCU 10 / WCU presplitWcu: read capacity during a write-only
            // phase costs money for nothing, and partition count is driven by the WCU present
            // at creation time and never changes afterward (partitions never merge). presplitWcu
            // defaults to loadWcu, so a config that doesn't set it behaves exactly as before.
            // When presplitWcu is higher than loadWcu, creating high and then dropping to
            // loadWcu keeps the extra partitions while paying the load rate, which is how a
            // write-throttling table gets more per-partition headroom than creating directly at
            // loadWcu would. This only works at creation time, so it is skipped entirely for a
            // table that already exists -- see the comment in that branch below.
            //
            // Each capacity DECREASE below (and the SWITCH and TEARDOWN decreases later in this
            // method) spends one of the table's per-UTC-day decrease allowance: DynamoDB permits
            // 4 decreases per day and then at most one per hour. A pre-split run spends three
            // (this drop, SWITCH, TEARDOWN) instead of two. This is not the comfortable margin an
            // earlier version of this comment claimed -- on 2026-09-10 a repeated-run series
            // exhausted the 4 and left the table stranded at 40,000 RCU (~$5.20/hr) for the rest
            // of the hour, because TEARDOWN's decrease was refused. That is what manageCapacity
            // exists for: a series raises capacity once and drops it once, spending one decrease
            // in total instead of one per run.
            boolean tableAlreadyExisted = admin.exists();
            if (cfg.skipLoad()) {
                // Read-only re-run: the dataset is already on the table from an earlier run.
                // Everything in this branch's alternatives -- CreateTable, the pre-split, the
                // capacity raise to loadWcu -- exists only to make writes possible, and this
                // run performs none. Fail loudly rather than silently measuring an empty table:
                // reads against a table with no items return misses at ~1 RCU apiece and yield
                // fast, plausible, completely meaningless latencies (the task-17 failure mode).
                if (!tableAlreadyExisted) {
                    throw new IllegalStateException(
                        "skipLoad=true but table " + cfg.table() + " does not exist. skipLoad "
                        + "re-reads a dataset an earlier run left behind; there is nothing here "
                        + "to read. Run with skipLoad=false to create and load it.");
                }
                System.out.println("LOAD: skipped (skipLoad=true) -- re-reading the dataset an "
                    + "earlier run left on " + cfg.table() + ". No CreateTable, no pre-split, "
                    + "no size-model probe, no writes.");
            } else if (tableAlreadyExisted) {
                System.out.println("LOAD: table already exists -- its partitioning is whatever "
                    + "it already was; the pre-split optimisation only applies at creation time, "
                    + "so it is not re-applied. Raising to " + cfg.loadWcu() + " WCU / 10 RCU");
                admin.createIfAbsent(10, cfg.loadWcu());   // no-op: table exists
                admin.awaitActiveWithCapacity(10, cfg.loadWcu(), Duration.ofMinutes(10));
            } else {
                long presplitPartitions = cfg.presplitWcu() / 1000;
                System.out.println("LOAD: creating table at " + cfg.presplitWcu() + " WCU / 10 "
                    + "RCU (~" + presplitPartitions + " partitions at creation, 1,000 WCU/s "
                    + "each)");
                admin.createIfAbsent(10, cfg.presplitWcu());
                admin.awaitActiveWithCapacity(10, cfg.presplitWcu(), Duration.ofMinutes(10));

                if (cfg.presplitWcu() != cfg.loadWcu()) {
                    System.out.println("LOAD: dropping WCU " + cfg.presplitWcu() + " -> "
                        + cfg.loadWcu() + " for the test; partition count stays at ~"
                        + presplitPartitions + ", so per-partition rate is now ~"
                        + (cfg.loadWcu() / presplitPartitions) + " WCU/s against the 1,000 "
                        + "hard limit");
                    admin.updateCapacity(10, cfg.loadWcu());
                    admin.awaitActiveWithCapacity(10, cfg.loadWcu(), Duration.ofMinutes(10));
                }
            }
            if (!cfg.skipLoad()) {
                SizeModelProbe.validate(ddb, cfg.table(), keys, factory, 100, cfg.resultsDir());

                // The checkpoint file lives outside resultsDir specifically so it survives a wipe
                // between runs (see Config.checkpointFile), but the table itself does NOT survive
                // between runs -- a fresh smoke run deletes and recreates it. CreationDateTime
                // changes every time that happens, even under the identical name, so it is what
                // ties a checkpoint to the specific table instance it was written against; see
                // LoadPhase's class javadoc for the task-17 incident this closes.
                LoadPhase.TableIdentity tableIdentity = new LoadPhase.TableIdentity(
                    cfg.table(), admin.describe().creationDateTime().toEpochMilli());
                LoadPhase.Source loadSource = LoadPhase.source(
                    tableIdentity, cfg.itemCount(), cfg.checkpointFile(), 0, 1);
                // Checkpointed continuously, once per ~1s control-loop tick, not only after LOAD
                // returns: a crash partway through the ~49-minute load must have something to
                // resume from. A checkpoint write failure is a recovery-aid problem, not a
                // correctness problem for the run in progress, so it is logged and swallowed
                // rather than allowed to kill the load.
                LongConsumer checkpointHook = completed -> {
                    try {
                        loadSource.checkpoint(completed);
                    } catch (IOException e) {
                        System.err.println("LOAD: checkpoint write failed (continuing): " + e.getMessage());
                    }
                };
                summaries.add(runPhase("LOAD", cfg, ddb, writer, cpu, runStart,
                    new PutWorkload(ddb, cfg.table(), keys, factory, LOAD_RAMP),
                    loadSource, cfg.loadWcu(), LOAD_RAMP, LOAD_WIN, null, checkpointHook));
                loadSource.checkpoint(cfg.itemCount());
            }

            // ---- SWITCH ----
            if (cfg.manageCapacity()) {
                System.out.println("SWITCH: WCU -> 10, RCU -> " + cfg.readRcu());
                admin.updateCapacity(cfg.readRcu(), 10);
                admin.awaitActiveWithCapacity(cfg.readRcu(), 10, Duration.ofMinutes(30));
            } else {
                // The caller owns capacity across a whole series of runs; verify, do not set.
                admin.requireCapacity(cfg.readRcu(), 10);
            }
            // Settling runs either way: keeping every run in a series identical matters more
            // than the couple of minutes saved when capacity did not actually change.
            System.out.println("SWITCH: settling 5 minutes");
            Thread.sleep(Duration.ofMinutes(5).toMillis());

            // ---- Segment-scoped read key selection (optional) ----
            // Evenly spreading the configured read rate across the WHOLE key space spreads it
            // across all ~40 partitions too (DynamoDB hashes keys to partitions), understating
            // per-partition load. When readSegmentsTotal > 0, run a parallel Scan ONCE here --
            // BEFORE either read phase's measurement window opens, so this scan's own consumed
            // capacity is never counted toward R-A/R-B's achieved-rate or throttle numbers; do
            // NOT move this call inside a measurement window -- and read only that known slice
            // of partitions for both read phases. See SegmentKeySelector's javadoc.
            IndexSource raSource;
            IndexSource rbSource;
            if (cfg.readSegmentsTotal() > 0) {
                System.out.println("SEGMENT-SELECT: scanning segments 0.." + (cfg.readSegmentsUsed() - 1)
                    + " of " + cfg.readSegmentsTotal());
                SegmentKeySelector.Result sel = SegmentKeySelector.select(
                    ddb, cfg.table(), cfg.itemCount(), cfg.readSegmentsUsed(), cfg.readSegmentsTotal());
                double perPartitionRcu =
                    cfg.readRcu() * cfg.targetFraction() / cfg.readSegmentsUsed();
                System.out.printf(
                    "SEGMENT-SELECT: keys=%d elapsedMs=%d consumedCapacityUnits=%.1f "
                    + "impliedPerPartitionRcu=%.0f (readRcu=%d x targetFraction=%.2f / "
                    + "readSegmentsUsed=%d)%n",
                    sel.indices().length, sel.elapsedNanos() / 1_000_000L, sel.consumedCapacityUnits(),
                    perPartitionRcu, cfg.readRcu(), cfg.targetFraction(), cfg.readSegmentsUsed());
                raSource = ReadPhase.cycleSource(sel.indices());
                rbSource = ReadPhase.cycleSource(sel.indices());
            } else {
                raSource = ReadPhase.cycleSource(keys);
                rbSource = ReadPhase.cycleSource(keys);
            }

            // ---- R-A: strongly consistent ----
            summaries.add(runPhase("R-A-strong", cfg, ddb, writer, cpu, runStart,
                new GetWorkload(ddb, cfg.table(), keys, true, RA_RAMP),
                raSource, cfg.readRcu(), RA_RAMP, RA_WIN, cfg.windowDuration(),
                null));

            // ---- R-B: eventually consistent ----
            summaries.add(runPhase("R-B-eventual", cfg, ddb, writer, cpu, runStart,
                new GetWorkload(ddb, cfg.table(), keys, false, RB_RAMP),
                rbSource, cfg.readRcu(), RB_RAMP, RB_WIN, cfg.windowDuration(),
                null));

            success = true;
        } finally {
            // TEARDOWN always runs, success or failure. Each step is wrapped in its own
            // try/catch: a failure here (e.g. UpdateTable throwing because the table was
            // never successfully created) must be logged loudly but must never mask whatever
            // exception is already propagating out of the try block above.
            if (!success) {
                System.err.println("TEARDOWN: run failed partway through; resetting capacity to "
                    + "RCU 10 / WCU 10 so the account is not billed for load/read capacity "
                    + "indefinitely");
            }
            if (cfg.manageCapacity()) {
                try {
                    admin.updateCapacity(10, 10);
                    System.out.println("TEARDOWN: RCU -> 10, WCU -> 10");
                } catch (Exception e) {
                    System.err.println("TEARDOWN: failed to reset table capacity to RCU 10 / WCU "
                        + "10 -- MANUAL INTERVENTION REQUIRED to stop ongoing billing: " + e);
                }
            } else {
                System.out.println("TEARDOWN: leaving capacity alone (manageCapacity=false) -- "
                    + "the caller raised it for the whole series and is responsible for dropping "
                    + "it when the series ends.");
            }
            try {
                writer.close();
            } catch (Exception e) {
                System.err.println("TEARDOWN: failed to close the record writer (some records "
                    + "may be lost): " + e);
            }
        }

        // Reached only on success: an exception from the try block above propagates past this
        // point once the finally has run, so results/DONE/self-stop are unreachable on failure.
        SummaryWriter.write(cfg.resultsDir().resolve("summary.json"), summaries);
        Files.writeString(cfg.resultsDir().resolve("DONE"), "ok\n");
        System.out.println("TEARDOWN: results in " + cfg.resultsDir());

        // The JFR filename given to -XX:StartFlightRecording only gets its data at JVM exit;
        // dumping it explicitly now (still well before exit) is what makes the recording
        // non-empty for the upload below. A no-op when the JVM wasn't started with
        // -XX:StartFlightRecording -- see JfrDump's javadoc.
        JfrDump.dumpIfActive(cfg.resultsDir().resolve("run.jfr"));

        // scripts/30-run.sh redirects the harness's own stdout/stderr to cfg.harnessLogFile()
        // (the phase-transition and violation record 40-collect.sh actually wants); Main can
        // only copy it into resultsDir so the generic upload below picks it up like any other
        // artifact. Skipped cleanly when it isn't there, e.g. every test, and any run not
        // launched the way 30-run.sh launches it.
        copyHarnessLogIfPresent(cfg.harnessLogFile(), cfg.resultsDir().resolve("ddblat.log"));

        // Upload everything in resultsDir to S3, DONE last -- see ResultsUpload's javadoc for
        // why the ordering matters. An empty s3Bucket (every test, and any standalone/local
        // run) is local-only operation and is skipped silently. A failure here must never be
        // confused with the run itself failing: the local copy on this instance's EBS volume
        // is what actually matters and is already complete and untouched by this step.
        boolean s3UploadFailed = false;
        try {
            boolean uploaded = ResultsUpload.runIfConfigured(
                cfg.resultsDir(), cfg.s3Bucket(), cfg.s3Prefix(),
                () -> S3ResultsUploader.create(Region.of(cfg.region()), cfg.s3Bucket()));
            if (uploaded) {
                System.out.println(
                    "TEARDOWN: uploaded results to s3://" + cfg.s3Bucket() + "/" + cfg.s3Prefix());
            }
        } catch (Exception e) {
            s3UploadFailed = true;
            System.err.println("############################################################");
            System.err.println("# S3 UPLOAD FAILED -- the run itself SUCCEEDED.");
            System.err.println("# Results are safe and complete on this instance at:");
            System.err.println("#   " + cfg.resultsDir());
            System.err.println("# They were NOT copied to s3://" + cfg.s3Bucket() + "/" + cfg.s3Prefix());
            System.err.println("# Cause: " + e);
            System.err.println("############################################################");
        }

        if (cfg.selfStop()) {
            try {
                String id = SelfStop.instanceId();
                System.out.println("TEARDOWN: stopping " + id);
                try (Ec2Client ec2 = Ec2Client.builder().region(Region.of(cfg.region())).build()) {
                    SelfStop.stop(ec2, id);
                }
            } catch (Exception e) {
                // Results are already safely written at this point -- failing to self-stop is
                // an inconvenience (the instance keeps running), not data loss, so this must
                // not end the process in a raw stack trace.
                System.err.println("TEARDOWN: results are complete, but the instance could not "
                    + "be self-stopped (not on EC2, or IMDSv2 unreachable): " + e.getMessage());
            }
        }

        // A distinct, documented, non-zero exit code -- rather than a raw stack trace or a
        // silent 0 -- so an operator or script watching this process cannot mistake "results
        // are on local disk but never reached S3" for either total success or total failure.
        // Deliberately checked last: self-stop above must still run even when the S3 upload
        // failed, since the local results are already safe and there is no reason to keep an
        // otherwise-idle instance billing while someone notices the log line.
        if (s3UploadFailed) {
            System.exit(S3_UPLOAD_FAILED_EXIT_CODE);
        }
    }

    private static SummaryWriter.PhaseSummary runPhase(
            String name, Config cfg, DynamoDbClient ddb, RecordWriter writer,
            java.util.function.DoubleSupplier cpu, long runStart,
            Workload workload, IndexSource source, long ceiling,
            byte rampId, byte winId, Duration window,
            LongConsumer progressHook) throws Exception {

        System.out.println(name + ": starting");
        // minCapacityUnits floors the bucket at CAPACITY_FLOOR_REQUESTS times the largest
        // single request this workload can ever charge it, so a low ceiling (small-scale smoke
        // runs) can never produce a capacity smaller than one request's cost -- which would
        // make acquire() unsatisfiable forever, see TokenBucket.acquire's javadoc -- and never
        // a capacity of exactly one request's cost either, see CAPACITY_FLOOR_REQUESTS's.
        TokenBucket bucket = new TokenBucket(
            ceiling * 0.10, 0.1, workload.maxCapacityUnits() * CAPACITY_FLOOR_REQUESTS);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(10_000_000_000L, 100);
        RampController ramp = new RampController(
            ceiling, cfg.targetFraction(), cfg.minRampNanos(), cfg.warmNanos(),
            30_000_000_000L);   // production: advance every 30 s
        WorkerPool pool = new WorkerPool(workload, bucket, meter,
            new PhaseStats(name + "-ramp", 1_000_000L), writer, source,
            cfg.initialThreads(), cfg.maxThreads(), runStart);

        PhaseRunner runner = new PhaseRunner(name, pool, bucket, meter, ramp, cpu,
            window == null ? Duration.ofDays(1) : window,
            Duration.ofMinutes(30), cfg.resultsDir().resolve(name + ".hlog"),
            rampId, winId, progressHook);
        PhaseRunner.WindowResult r = runner.run();
        bucket.close();

        System.out.printf("%s: valid=%s p50=%.0fus p99=%.0fus p99.9=%.0fus%n",
            name, r.valid(), r.stats().raw().p50(), r.stats().raw().p99(), r.stats().raw().p999());
        r.violations().forEach(v -> System.out.println("  VIOLATION: " + v));

        return new SummaryWriter.PhaseSummary(name, r.stats().raw(), r.stats().corrected(),
            r.stats().throttles(), r.stats().retries(), r.stats().hits(), r.stats().misses(),
            r.achievedUnitsPerSec(), r.valid(), r.violations());
    }

    /**
     * Copies {@code source} to {@code dest} if {@code source} exists, so it gets swept up by
     * the generic "upload every regular file in resultsDir" step below. Never throws: an
     * unreadable or missing source log is a cosmetic loss (the phase-transition record), not a
     * reason to fail a run whose actual results are already complete and safe.
     */
    private static void copyHarnessLogIfPresent(Path source, Path dest) {
        try {
            if (!Files.isRegularFile(source)) {
                return;
            }
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("WARNING: failed to copy harness log from " + source + " to "
                + dest + " -- continuing without it: " + e);
        }
    }

    private static java.util.function.DoubleSupplier cpuSupplier() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun::getCpuLoad;
        }
        return () -> 0.0;
    }
}
