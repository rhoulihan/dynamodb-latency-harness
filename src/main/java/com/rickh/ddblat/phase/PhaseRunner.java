package com.rickh.ddblat.phase;

import com.rickh.ddblat.metrics.CapacityMeter;
import com.rickh.ddblat.metrics.PhaseStats;
import com.rickh.ddblat.rate.RampController;
import com.rickh.ddblat.rate.TokenBucket;
import com.rickh.ddblat.worker.WorkerPool;

import com.rickh.ddblat.report.IntervalLogger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.LongConsumer;

/**
 * Owns the 1 Hz control loop and the in-process half of the validity gate.
 * Ramp traffic is logged under the ramp phase id and never counted toward a headline
 * percentile; the window opens only once the controller reaches HOLDING, which cannot
 * happen before the minimum ramp duration has elapsed.
 *
 * Every tick of the control loop -- ramp state, target, achieved throughput, the bucket's own
 * rate/capacity/tokens, thread and completion counts, client CPU, and window status -- is
 * logged as one {@code TICK } line to stdout, which {@code scripts/30-run.sh} redirects into
 * the harness log that is uploaded to S3. This is the instrument a real smoke run is missing:
 * task-17's read-phase stall was diagnosed after the fact from CloudWatch server-side metrics
 * because nothing in the process logged its own control state. A {@code PHASE-START} line
 * once per phase records the inputs that determined the run's targets and thread counts, and
 * a {@code STALL} line fires if achieved throughput sits below 50% of target for more than 60
 * consecutive ticks while the ramp is not yet HOLDING -- see {@link #run} for both.
 */
public final class PhaseRunner {

    public record WindowResult(PhaseStats stats, boolean valid,
                               List<String> violations, double achievedUnitsPerSec,
                               int evaluatedTicks) {}

    /** Production control-loop cadence: one tick per second, as PhaseRunner has always run. */
    public static final Duration DEFAULT_TICK_INTERVAL = Duration.ofSeconds(1);

    /**
     * How many consecutive ticks of "achieved < 50% of target, ramp not yet HOLDING" trigger
     * a STALL log line. At the production 1 Hz cadence this is 60 consecutive seconds, per
     * spec; expressed in ticks (not nanos) so a compressed tickInterval in a test reaches the
     * same guard in real seconds proportionally faster, exactly like {@link
     * RampController}'s advanceIntervalNanos parameter.
     */
    static final int STALL_THRESHOLD_TICKS = 60;

    private static final double STALL_ACHIEVED_FRACTION = 0.50;

    /**
     * Minimum completed reads with a hit/miss verdict before the miss-rate guard judges
     * anything: a couple of stray misses in the first handful of samples is noise, not a
     * signal, whereas task-17's actual incident ran at ~93% misses over thousands of reads.
     */
    static final long MISS_RATE_MIN_SAMPLES = 100;

    /**
     * Above this fraction of misses, a read phase is no longer measuring real item reads --
     * see task-17, where a DynamoDB miss (~1 RCU) masquerading as a hit-sized response
     * collapsed the ramp's achieved-CU/s signal and would have produced a plausible-looking
     * but meaningless P99. Applied both to the live per-tick warning below and to the
     * phase's own end-of-window validity criteria.
     */
    static final double MISS_RATE_THRESHOLD = 0.01;

    /**
     * Bounds how long the exhaustion branch below waits for workers that already hold a
     * dequeued index to finish it naturally before falling back to {@link WorkerPool#stop()}'s
     * interrupt. Comfortably above {@code ClientSettings.forThreads}'s 10s apiCallTimeout --
     * the SDK itself guarantees no single call runs longer than that -- with margin for a
     * worker still waiting on {@code TokenBucket.acquire} for its turn at the current target
     * rate. See {@link WorkerPool#drain}.
     */
    static final Duration EXHAUSTION_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private final String phaseName;
    private final WorkerPool pool;
    private final TokenBucket bucket;
    private final CapacityMeter meter;
    private final RampController ramp;
    private final DoubleSupplier cpu;
    private final Duration windowDuration;
    private final Duration maxRampDuration;
    private final Path hlogFile;
    private final byte rampPhaseId;
    private final byte windowPhaseId;
    private final LongConsumer progressHook;
    private final long tickNanos;

    /**
     * maxRampDuration bounds the whole pre-window phase. Without it, any ramp condition
     * that never clears -- client CPU pinned above the limit, achieved throughput stuck
     * below 95% of target -- leaves the controller short of HOLDING forever, and with an
     * unbounded work source the loop never terminates. A stuck ramp must fail the phase
     * loudly, not strand a paid multi-hour run.
     */
    public PhaseRunner(String phaseName, WorkerPool pool, TokenBucket bucket, CapacityMeter meter,
                       RampController ramp, DoubleSupplier cpu, Duration windowDuration,
                       Duration maxRampDuration, Path hlogFile,
                       byte rampPhaseId, byte windowPhaseId) {
        this(phaseName, pool, bucket, meter, ramp, cpu, windowDuration, maxRampDuration, hlogFile,
            rampPhaseId, windowPhaseId, null, DEFAULT_TICK_INTERVAL);
    }

    /**
     * progressHook, if non-null, is invoked once per ~1 Hz control-loop tick with
     * {@code pool.completed()} -- the running count of requests this phase has finished so
     * far, ramp and window traffic alike. LOAD uses this to persist a resumable checkpoint
     * continuously through a run that can last tens of minutes, rather than only after the
     * phase returns (see {@code Main}, which would otherwise have nothing to resume from on
     * a mid-load crash). The read phases have nothing to resume and pass null.
     */
    public PhaseRunner(String phaseName, WorkerPool pool, TokenBucket bucket, CapacityMeter meter,
                       RampController ramp, DoubleSupplier cpu, Duration windowDuration,
                       Duration maxRampDuration, Path hlogFile,
                       byte rampPhaseId, byte windowPhaseId, LongConsumer progressHook) {
        this(phaseName, pool, bucket, meter, ramp, cpu, windowDuration, maxRampDuration, hlogFile,
            rampPhaseId, windowPhaseId, progressHook, DEFAULT_TICK_INTERVAL);
    }

    /**
     * Full constructor. tickInterval is a parameter, not a hardcoded constant, purely so a
     * test exercising the STALL guard's 60-consecutive-tick threshold does not need 60 real
     * wall-clock seconds to do it -- production always passes {@link #DEFAULT_TICK_INTERVAL}
     * via the convenience constructors above.
     */
    public PhaseRunner(String phaseName, WorkerPool pool, TokenBucket bucket, CapacityMeter meter,
                       RampController ramp, DoubleSupplier cpu, Duration windowDuration,
                       Duration maxRampDuration, Path hlogFile,
                       byte rampPhaseId, byte windowPhaseId, LongConsumer progressHook,
                       Duration tickInterval) {
        this.phaseName = phaseName;
        this.pool = pool;
        this.bucket = bucket;
        this.meter = meter;
        this.ramp = ramp;
        this.cpu = cpu;
        this.windowDuration = windowDuration;
        this.maxRampDuration = maxRampDuration;
        this.hlogFile = hlogFile;
        this.rampPhaseId = rampPhaseId;
        this.windowPhaseId = windowPhaseId;
        this.progressHook = progressHook;
        this.tickNanos = tickInterval.toNanos();
    }

    public WindowResult run() throws InterruptedException {
        final long phaseStartNanos = System.nanoTime();
        PhaseStats rampStats = new PhaseStats("ramp-" + rampPhaseId, tickNanos);
        pool.setStats(rampStats);
        pool.setPhaseId(rampPhaseId);
        pool.start();

        logPhaseStart();

        PhaseStats windowStats = null;
        IntervalLogger hlog = null;
        long windowOpenedAt = 0;
        long lastThrottles = 0;
        double maxCpu = 0;
        int onTargetTicks = 0, totalWindowTicks = 0;
        double lastAchieved = 0, lastTarget = 0;
        long tickNumber = 0;
        int stallStreakTicks = 0;
        List<String> violations = new ArrayList<>();

        try {
            while (true) {
                Thread.sleep(tickNanos / 1_000_000L);
                long now = System.nanoTime();
                tickNumber++;

                if (progressHook != null) progressHook.accept(pool.completed());

                PhaseStats active = windowStats != null ? windowStats : rampStats;
                double achieved = meter.unitsPerSecond(now);
                lastAchieved = achieved;
                long throttlesNow = active.throttles();
                int throttleDelta = (int) (throttlesNow - lastThrottles);
                lastThrottles = throttlesNow;
                double cpuNow = cpu.getAsDouble();
                maxCpu = Math.max(maxCpu, cpuNow);

                RampController.Decision d = ramp.tick(now, achieved, throttleDelta, cpuNow,
                    bucket.isAtCap(), bucket.continuouslyAtCapNanos(now));
                bucket.setRate(d.targetUnitsPerSec());
                lastTarget = d.targetUnitsPerSec();
                if (d.threadDelta() > 0) pool.addThreads(d.threadDelta());

                long hitsNow = active.hits();
                long missesNow = active.misses();

                boolean windowOpenNow = windowStats != null;
                logTick(tickNumber, now - phaseStartNanos, d, achieved, cpuNow, windowOpenNow,
                    totalWindowTicks, hitsNow, missesNow);

                // Part 3's guard: a ramp that cannot advance (not yet HOLDING) while achieved
                // throughput sits far below whatever target it is currently holding is exactly
                // the shape of the task-17 field incident (14 CU/s against a 100 CU/s floor
                // target). This never aborts the phase -- it only makes a repeat of that
                // incident unmissable in the log instead of requiring CloudWatch archaeology.
                if (d.state() != RampController.State.HOLDING
                        && achieved < STALL_ACHIEVED_FRACTION * d.targetUnitsPerSec()) {
                    stallStreakTicks++;
                } else {
                    stallStreakTicks = 0;
                }
                if (stallStreakTicks > STALL_THRESHOLD_TICKS) {
                    logStall(stallStreakTicks, d, achieved);
                }

                // task-17's real root cause: a stale resume checkpoint silently loaded a
                // fraction of the dataset, so the read phases queried mostly-absent keys. A
                // miss is not an error DynamoDB reports -- it is a normal, successful response
                // with no item in it -- so the only way to catch this live is to watch the
                // hit/miss ratio itself.
                long verdicts = hitsNow + missesNow;
                if (verdicts >= MISS_RATE_MIN_SAMPLES) {
                    double missRate = (double) missesNow / verdicts;
                    if (missRate > MISS_RATE_THRESHOLD) {
                        logMissRateWarning(hitsNow, missesNow, missRate);
                    }
                }

                if (windowStats == null && d.state() == RampController.State.HOLDING) {
                    windowStats = new PhaseStats("window-" + windowPhaseId, tickNanos);
                    pool.setStats(windowStats);
                    pool.setPhaseId(windowPhaseId);
                    windowOpenedAt = now;
                    maxCpu = cpuNow;
                    lastThrottles = 0;
                    if (hlogFile != null) {
                        try {
                            hlog = new IntervalLogger(hlogFile, windowStats::rawHistogram,
                                                      Duration.ofSeconds(10));
                            hlog.start();
                        } catch (java.io.IOException e) {
                            violations.add("could not open interval log: " + e.getMessage());
                        }
                    }
                    continue;
                }

                if (windowStats != null) {
                    // The capacity meter is a sliding window, so for one full period after the
                    // measurement window opens it still contains ramp traffic. Judging the
                    // achieved-vs-target criterion before it has flushed measures the ramp, not
                    // the window. In a 20-minute window that would be ~10 of 1200 ticks -- it
                    // would pass on ratio rather than on correctness.
                    if (now - windowOpenedAt >= meter.windowNanos()) {
                        totalWindowTicks++;
                        if (Math.abs(achieved - d.targetUnitsPerSec()) <= 0.02 * d.targetUnitsPerSec()) {
                            onTargetTicks++;
                        }
                    }
                    if (now - windowOpenedAt >= windowDuration.toNanos()) break;
                }

                if (windowStats == null && now - phaseStartNanos >= maxRampDuration.toNanos()) {
                    windowStats = rampStats;
                    violations.add(String.format(
                        "ramp did not reach %.0f%% of ceiling within %s (last target %.0f CU/s, "
                        + "achieved %.0f CU/s): %s",
                        100 * 0.90, maxRampDuration, d.targetUnitsPerSec(), achieved, d.reason()));
                    break;
                }

                if (pool.exhausted()) {
                    if (windowStats == null) {
                        windowStats = rampStats;
                        violations.add("work exhausted before the measurement window opened");
                    }
                    // exhausted() flipped the instant the IndexSource handed out its LAST
                    // index, not when the last request completed: some other worker can
                    // easily still be holding an already-dequeued index right now, most
                    // commonly parked in TokenBucket.acquire waiting its turn. Breaking out
                    // and letting the finally below call pool.stop() immediately would
                    // interrupt that worker before it ever executes -- silently abandoning
                    // whichever index/key it was holding (see WorkerPool.drain's javadoc).
                    // Give every such worker a bounded chance to finish naturally first.
                    int abandoned = pool.drain(EXHAUSTION_DRAIN_TIMEOUT);
                    if (abandoned > 0) {
                        System.err.println("DRAIN-TIMEOUT phase=" + phaseName
                            + " abandonedWorkers=" + abandoned
                            + " drainTimeout=" + EXHAUSTION_DRAIN_TIMEOUT
                            + " -- these workers did not finish their already-in-flight "
                            + "request within the drain window and are about to be "
                            + "interrupted; whatever index/key each was holding will never "
                            + "be written or recorded");
                    }
                    break;
                }
            }
        } finally {
            // pool.stop() and the interval logger's close() must run even if an unchecked
            // exception from cpu.getAsDouble(), ramp.tick(), bucket.setRate(), or
            // pool.addThreads() escapes the control loop above -- otherwise a single bad
            // tick leaks every worker thread (see WorkerPool.stop()'s own javadoc for what
            // that costs downstream) and leaves the .hlog file handle open.
            pool.stop();
            if (hlog != null) {
                try {
                    hlog.close();
                } catch (java.io.IOException e) {
                    violations.add("could not close interval log: " + e.getMessage());
                }
            }
        }

        // Spec section 11, the five criteria checkable in process.
        if (windowStats.throttles() > 0) {
            violations.add("throttles inside window: " + windowStats.throttles());
        }
        long count = windowStats.raw().count();
        if (count > 0 && windowStats.retries() > count / 10_000) {
            violations.add("retried requests exceed 0.01%: " + windowStats.retries() + "/" + count);
        }
        if (maxCpu >= 0.70) {
            violations.add(String.format("client CPU reached %.2f, limit 0.70", maxCpu));
        }
        if (totalWindowTicks > 0 && onTargetTicks < 0.95 * totalWindowTicks) {
            violations.add(String.format(
                "achieved rate within 2%% of target for only %d/%d ticks, need 95%% "
                + "(last achieved %.0f CU/s vs target %.0f CU/s)",
                onTargetTicks, totalWindowTicks, lastAchieved, lastTarget));
        }
        if (count == 0) violations.add("window recorded no samples");

        // Beyond spec section 11's original five: task-17. A high miss rate is not a
        // performance quirk, it is a validity failure -- the window measured latency and
        // achieved-CU/s against absent data, which is not what this phase claims to measure.
        // Gated on the same minimum sample size as the live warning so a short/degenerate
        // window (already caught by "window recorded no samples" above) does not also fail on
        // a handful of noisy verdicts.
        long windowVerdicts = windowStats.hits() + windowStats.misses();
        if (windowVerdicts >= MISS_RATE_MIN_SAMPLES) {
            double windowMissRate = (double) windowStats.misses() / windowVerdicts;
            if (windowMissRate > MISS_RATE_THRESHOLD) {
                violations.add(String.format(
                    "miss rate %.2f%% exceeds validity threshold %.2f%% (%d misses / %d reads): "
                    + "this phase measured latency and achieved-CU/s against missing data, not "
                    + "real item reads",
                    100 * windowMissRate, 100 * MISS_RATE_THRESHOLD,
                    windowStats.misses(), windowVerdicts));
            }
        }

        return new WindowResult(windowStats, violations.isEmpty(), violations, lastAchieved,
            totalWindowTicks);
    }

    /**
     * Logged once, before the control loop's first tick: everything that determined this
     * phase's targets and thread counts, so a question like "why did the ramp float at 100
     * CU/s?" is answered by this one line instead of re-deriving it from source. See
     * task-17's report -- most of the wrong-diagnosis time on the field incident would have
     * been settled instantly by this line.
     */
    private void logPhaseStart() {
        TokenBucket.Snapshot snap = bucket.snapshot();
        System.out.println("PHASE-START phase=" + phaseName
            + " ceiling=" + fmt(ramp.ceiling())
            + " targetFraction=" + ramp.targetFraction()
            + " minTarget=" + fmt(ramp.minTarget())
            + " maxTarget=" + fmt(ramp.maxTarget())
            + " maxCapacityUnits=" + fmt(pool.maxCapacityUnits())
            + " bucketInitialRate=" + fmt(snap.rateUnitsPerSecond())
            + " bucketInitialCapacity=" + fmt(snap.capacityUnits())
            + " initialThreads=" + pool.initialThreadCount()
            + " maxThreads=" + pool.maxThreadCount());
    }

    /**
     * One line per control-loop tick, greppable on the {@code TICK } prefix. Deliberately
     * flat key=value pairs rather than JSON: a 2.5-hour run produces ~9,000 of these, and the
     * harness log they land in (see the class javadoc) is read with grep/awk, not a parser.
     */
    private void logTick(long tickNumber, long nanosSincePhaseStart, RampController.Decision d,
            double achievedUnitsPerSec, double cpuNow, boolean windowOpen, int evaluatedTicks,
            long hits, long misses) {
        TokenBucket.Snapshot snap = bucket.snapshot();
        StringBuilder sb = new StringBuilder(256);
        sb.append("TICK phase=").append(phaseName)
          .append(" tick=").append(tickNumber)
          .append(" sec=").append(fmt(nanosSincePhaseStart / 1_000_000_000.0))
          .append(" rampState=").append(d.state())
          .append(" target=").append(fmt(d.targetUnitsPerSec()))
          .append(" reason=\"").append(d.reason()).append('"')
          .append(" achievedCUs=").append(fmt(achievedUnitsPerSec))
          .append(" bucketRate=").append(fmt(snap.rateUnitsPerSecond()))
          .append(" bucketCapacity=").append(fmt(snap.capacityUnits()))
          .append(" bucketTokens=").append(fmt(snap.availableTokens()))
          .append(" threads=").append(pool.threadCount())
          .append(" completed=").append(pool.completed())
          .append(" retried=").append(pool.retriedRequests())
          .append(" hits=").append(hits)
          .append(" misses=").append(misses)
          .append(" cpu=").append(fmt(cpuNow))
          .append(" windowOpen=").append(windowOpen);
        if (windowOpen) {
            sb.append(" evaluatedTicks=").append(evaluatedTicks);
        }
        System.out.println(sb);
    }

    /**
     * Fires once the cumulative hit/miss ratio for the currently active phase segment (ramp
     * or window, whichever {@code active} is) crosses {@link #MISS_RATE_THRESHOLD} with at
     * least {@link #MISS_RATE_MIN_SAMPLES} verdicts recorded, and keeps firing every tick
     * thereafter for as long as it holds -- same pattern as {@link #logStall}. This never
     * aborts the phase; the end-of-window validity check in {@link #run} is what actually
     * fails a run on this. Printed to stderr with a distinct "MISS-RATE" marker.
     */
    private void logMissRateWarning(long hits, long misses, double missRate) {
        System.err.println("MISS-RATE phase=" + phaseName
            + " hits=" + hits
            + " misses=" + misses
            + " missRate=" + fmt(missRate * 100) + "%"
            + " -- reads are missing data; this phase's latency and achieved-CU/s numbers are "
            + "not representative of real item reads (see task-17)");
    }

    /**
     * Fires once achieved throughput has sat below 50% of target for more than {@link
     * #STALL_THRESHOLD_TICKS} consecutive ticks with the ramp still short of HOLDING, and
     * keeps firing every tick thereafter for as long as the condition holds -- this never
     * aborts the phase, it only guarantees a stall like task-17's announces itself in the log
     * instead of requiring CloudWatch archaeology after the fact. Printed to stderr (still
     * captured into the same harness log as stdout, see the class javadoc) with a distinct
     * "STALL" marker so it stands out from -- and is independently greppable alongside -- the
     * routine TICK lines.
     */
    private void logStall(int consecutiveTicks, RampController.Decision d, double achievedUnitsPerSec) {
        TokenBucket.Snapshot snap = bucket.snapshot();
        System.err.println("STALL phase=" + phaseName
            + " consecutiveTicks=" + consecutiveTicks
            + " target=" + fmt(d.targetUnitsPerSec())
            + " achieved=" + fmt(achievedUnitsPerSec)
            + " bucketTokens=" + fmt(snap.availableTokens())
            + " bucketCapacity=" + fmt(snap.capacityUnits())
            + " rampState=" + d.state()
            + " rampReason=\"" + d.reason() + "\"");
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
