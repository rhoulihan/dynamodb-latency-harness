package com.rickh.ddblat.phase;

import com.rickh.ddblat.metrics.*;
import com.rickh.ddblat.rate.*;
import com.rickh.ddblat.record.RecordWriter;
import com.rickh.ddblat.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PhaseRunnerTest {

    @TempDir Path tmp;

    private static Workload fast() {
        return new Workload() {
            public Outcome execute(int i) { return new Outcome(200_000L, 1.0, 1, 0, false); }
            public double estimatedCapacityUnits(int i) { return 1.0; }
            public double maxCapacityUnits() { return 1.0; }
            public byte phaseId() { return 0; }
        };
    }

    @Test
    void aCleanRunProducesAValidWindow() throws Exception {
        TokenBucket bucket = new TokenBucket(4000, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(1_000_000_000L, 100);  // 10ms buckets: ~1% bias, as production
        RecordWriter writer = new RecordWriter(tmp.resolve("p.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        WorkerPool pool = new WorkerPool(fast(), bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> i.getAndIncrement(), 8, 32, System.nanoTime());

        // warm 200ms, min ramp 400ms, 30s window -- compressed so the test runs in seconds.
        // The window is long enough (~29 evaluated ticks, once the capacity meter's 1s
        // flush delay is subtracted) that the 95%-of-ticks criterion can tolerate one
        // stray tick, rather than demanding perfection from 5-6 ticks.
        RampController ramp = new RampController(4000, 0.9, 400_000_000L, 200_000_000L, 50_000_000L);
        PhaseRunner runner = new PhaseRunner("test-phase", pool, bucket, meter, ramp,
            () -> 0.10, Duration.ofSeconds(30), Duration.ofSeconds(60), tmp.resolve("a.hlog"), (byte) 0, (byte) 1);

        PhaseRunner.WindowResult result = runner.run();
        writer.close();
        bucket.close();

        assertThat(result.valid()).as("violations: %s", result.violations()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.stats().raw().count()).isPositive();
    }

    @Test
    void highClientCpuInvalidatesTheWindow() throws Exception {
        TokenBucket bucket = new TokenBucket(4000, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(1_000_000_000L, 100);  // 10ms buckets: ~1% bias, as production
        RecordWriter writer = new RecordWriter(tmp.resolve("p2.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        WorkerPool pool = new WorkerPool(fast(), bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> i.getAndIncrement(), 8, 32, System.nanoTime());

        RampController ramp = new RampController(4000, 0.9, 400_000_000L, 200_000_000L, 50_000_000L);
        PhaseRunner runner = new PhaseRunner("test-phase", pool, bucket, meter, ramp,
            () -> 0.95, Duration.ofSeconds(1), Duration.ofSeconds(5), null, (byte) 0, (byte) 1);

        PhaseRunner.WindowResult result = runner.run();
        writer.close();
        bucket.close();

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("CPU"));
    }

    @Test
    void aThrottleInsideTheWindowInvalidatesIt() throws Exception {
        // Throttles by ELAPSED TIME, not request count. Ticks are 1/second and the ramp
        // needs ~8 advances to climb from the 10% floor to the 90% target, so HOLDING (and
        // the window) arrives around t~=10-11s. Staying healthy for the first 12s therefore
        // guarantees the throttle onset lands after the window has opened, inside it -- the
        // path spec 11 criterion 1 ("a throttle inside an OPEN window invalidates it") is
        // meant to exercise. A request-count threshold cannot make this guarantee: while
        // achieved throughput is collapsed by throttling, RampController never reaches
        // HOLDING (it requires achieved >= 95% of target), so no window ever opens and the
        // test would only ever pass via the 60s maxRampDuration deadline message.
        Workload throttler = new Workload() {
            final long startNanos = System.nanoTime();
            public Outcome execute(int i) {
                boolean t = System.nanoTime() - startNanos >= 12_000_000_000L;
                return new Outcome(200_000L, t ? 0 : 1.0, 1, t ? 1 : 0, t);
            }
            public double estimatedCapacityUnits(int i) { return 1.0; }
            public double maxCapacityUnits() { return 1.0; }
            public byte phaseId() { return 0; }
        };
        TokenBucket bucket = new TokenBucket(4000, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(1_000_000_000L, 100);  // 10ms buckets: ~1% bias, as production
        RecordWriter writer = new RecordWriter(tmp.resolve("p3.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        WorkerPool pool = new WorkerPool(throttler, bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> i.getAndIncrement(), 8, 32, System.nanoTime());

        RampController ramp = new RampController(4000, 0.9, 400_000_000L, 200_000_000L, 50_000_000L);
        // windowDuration = 10s (not the default 1s) so the window is still open when the
        // throttling begins at t~=12s. maxRampDuration stays at 60s as a backstop.
        PhaseRunner runner = new PhaseRunner("test-phase", pool, bucket, meter, ramp,
            () -> 0.10, Duration.ofSeconds(10), Duration.ofSeconds(60), null, (byte) 0, (byte) 1);

        PhaseRunner.WindowResult result = runner.run();
        writer.close();
        bucket.close();

        assertThat(result.valid()).isFalse();
        // Assert on the specific violation text PhaseRunner produces for throttles inside
        // an open window, not a bare "throttle" substring -- the ramp-deadline message also
        // embeds RampController's throttle reason text and would satisfy a looser match
        // even when no window ever opened.
        assertThat(result.violations()).anyMatch(v -> v.startsWith("throttles inside window:"));
        // And prove the throttles were counted against the WINDOW's stats, not the ramp's.
        assertThat(result.stats().throttles()).isGreaterThan(0L);
    }

    @Test
    void evaluatedTicksExcludeTheCapacityMeterSettlePeriod() throws Exception {
        // A meter window measurably wider than one control-loop tick (1s), so the settle
        // gate -- which withholds achieved-vs-target judgment until a full meter window has
        // elapsed since the window opened, because the meter's sliding window still holds
        // ramp traffic until then -- excludes a clearly observable number of ticks rather
        // than a single boundary tick that real wall-clock scheduling jitter could push
        // either way. 2s (200 x 10ms buckets, same per-bucket granularity as production)
        // reliably excludes the first 1-2 ticks after the window opens without stretching
        // the ramp's settle time unreasonably.
        TokenBucket bucket = new TokenBucket(4000, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(2_000_000_000L, 200);
        RecordWriter writer = new RecordWriter(tmp.resolve("p4.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        WorkerPool pool = new WorkerPool(fast(), bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> i.getAndIncrement(), 8, 32, System.nanoTime());

        RampController ramp = new RampController(4000, 0.9, 400_000_000L, 200_000_000L, 50_000_000L);
        Duration windowDuration = Duration.ofSeconds(15);
        PhaseRunner runner = new PhaseRunner("test-phase", pool, bucket, meter, ramp,
            () -> 0.10, windowDuration, Duration.ofSeconds(90), null, (byte) 0, (byte) 1);

        PhaseRunner.WindowResult result = runner.run();
        writer.close();
        bucket.close();

        // Ungated, ticks counted while the window is open would be ~windowSeconds (15):
        // one tick roughly every second for the whole window. The settle gate withholds the
        // first ~2s (the meter window) worth of them, so evaluatedTicks should land well
        // below windowSeconds -- pinning that the gate is actually excluding ticks, not a
        // no-op.
        long windowSeconds = windowDuration.toSeconds();
        assertThat(result.evaluatedTicks())
            .as("evaluatedTicks=%d should exclude the ~2s capacity-meter settle period out "
                + "of a %ds window, not count every tick the window was open",
                result.evaluatedTicks(), windowSeconds)
            .isPositive()
            .isLessThanOrEqualTo((int) windowSeconds - 1);
    }

    @Test
    void progressHookFiresRepeatedlyWithNonDecreasingCompletedCounts() throws Exception {
        // Client CPU pinned above the 0.70 limit, same trick as highClientCpuInvalidatesTheWindow:
        // the ramp never reaches HOLDING, so the phase runs for the full maxRampDuration (4s)
        // giving the ~1 Hz control loop several ticks to invoke the hook, without needing a
        // real measurement window to open.
        TokenBucket bucket = new TokenBucket(4000, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(1_000_000_000L, 100);
        RecordWriter writer = new RecordWriter(tmp.resolve("p5.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        WorkerPool pool = new WorkerPool(fast(), bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> i.getAndIncrement(), 8, 32, System.nanoTime());

        RampController ramp = new RampController(4000, 0.9, 400_000_000L, 200_000_000L, 50_000_000L);
        List<Long> hookCalls = Collections.synchronizedList(new ArrayList<>());
        PhaseRunner runner = new PhaseRunner("test-phase", pool, bucket, meter, ramp,
            () -> 0.95, Duration.ofSeconds(1), Duration.ofSeconds(4), null, (byte) 0, (byte) 1,
            (long completed) -> hookCalls.add(completed));

        runner.run();
        writer.close();
        bucket.close();

        assertThat(hookCalls.size())
            .as("hook should fire once per ~1s control-loop tick over a 4s phase")
            .isGreaterThan(1);
        for (int k = 1; k < hookCalls.size(); k++) {
            assertThat(hookCalls.get(k))
                .as("pool.completed() must never go backwards between ticks")
                .isGreaterThanOrEqualTo(hookCalls.get(k - 1));
        }
    }

    /**
     * Task-17 Part 2: PhaseRunner must log its own control state instead of leaving the only
     * record in CloudWatch. This exercises the full constructor with a compressed tickInterval
     * (50ms instead of production's 1s) purely so the test does not need real multi-second
     * waits to observe several ticks -- the ramp never reaches HOLDING here (minRampNanos is
     * far longer than maxRampDuration), so the phase runs its full maxRampDuration and bails
     * out via the ramp-deadline violation, which is fine: this test only cares that PHASE-START
     * is logged once and TICK is logged every tick, with all of the required fields.
     */
    @Test
    void logsOnePhaseStartLineAndOneTickLinePerControlLoopTick() throws Exception {
        TokenBucket bucket = new TokenBucket(1000, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(200_000_000L, 20);
        RecordWriter writer = new RecordWriter(tmp.resolve("p6.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        WorkerPool pool = new WorkerPool(fast(), bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> i.getAndIncrement(), 2, 4, System.nanoTime());

        // minRampNanos (10s) far exceeds maxRampDuration (500ms) below, so HOLDING is never
        // reached and the whole run is spent producing TICK lines under a known tick count.
        RampController ramp = new RampController(1000, 0.9, 10_000_000_000L, 0L, 50_000_000L);
        Duration tickInterval = Duration.ofMillis(50);
        PhaseRunner runner = new PhaseRunner("tick-test-phase", pool, bucket, meter, ramp,
            () -> 0.10, Duration.ofSeconds(60), Duration.ofMillis(500), null, (byte) 0, (byte) 1,
            null, tickInterval);

        PrintStream realOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        List<String> lines;
        try {
            System.setOut(new PrintStream(captured, true));
            runner.run();
        } finally {
            System.setOut(realOut);
            writer.close();
            bucket.close();
        }
        lines = Arrays.asList(captured.toString().split("\\R"));

        List<String> phaseStartLines = lines.stream()
            .filter(l -> l.startsWith("PHASE-START phase=tick-test-phase")).toList();
        List<String> tickLines = lines.stream()
            .filter(l -> l.startsWith("TICK phase=tick-test-phase")).toList();

        assertThat(phaseStartLines)
            .as("exactly one PHASE-START line, logged before any TICK line")
            .hasSize(1);
        assertThat(lines.indexOf(phaseStartLines.get(0)))
            .isLessThan(lines.indexOf(tickLines.get(0)));

        // ~500ms / 50ms tick interval = ~10 ticks; allow generous slack for scheduling jitter.
        assertThat(tickLines.size())
            .as("one TICK line per ~50ms control-loop tick over a 500ms phase")
            .isGreaterThanOrEqualTo(5);

        for (String field : new String[] {
                "tick=", "sec=", "rampState=", "target=", "reason=", "achievedCUs=",
                "bucketRate=", "bucketCapacity=", "bucketTokens=", "threads=", "completed=",
                "retried=", "hits=", "misses=", "cpu=", "windowOpen="}) {
            assertThat(tickLines.get(0)).as("TICK line missing field %s: %s", field, tickLines.get(0))
                .contains(field);
        }
        assertThat(phaseStartLines.get(0)).contains("ceiling=").contains("targetFraction=")
            .contains("minTarget=").contains("maxTarget=").contains("maxCapacityUnits=")
            .contains("bucketInitialRate=").contains("bucketInitialCapacity=")
            .contains("initialThreads=2").contains("maxThreads=4");
    }

    /**
     * Task-17 Part 3: the STALL guard. A single worker thread with an artificially slow
     * (50ms) simulated request keeps achieved throughput far below the 100 CU/s floor target
     * the ramp holds (minRampNanos is far longer than the run, so the ramp never reaches
     * HOLDING and stays blocked at its floor) -- exactly the shape of the task-17 field
     * incident. tickInterval is compressed to 20ms so the 60-consecutive-tick threshold is
     * crossed in a bit over a second of real time rather than a real 60 seconds.
     */
    @Test
    void stallWarningFiresAfterSixtyConsecutiveTicksBelowHalfOfTarget() throws Exception {
        Workload slow = new Workload() {
            public Outcome execute(int i) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Outcome(50_000_000L, 1.0, 1, 0, false);
            }
            public double estimatedCapacityUnits(int i) { return 1.0; }
            public double maxCapacityUnits() { return 1.0; }
            public byte phaseId() { return 0; }
        };

        TokenBucket bucket = new TokenBucket(100, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(200_000_000L, 20);
        RecordWriter writer = new RecordWriter(tmp.resolve("p7.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        // A single, fixed-size worker: with a 50ms simulated request, this thread can never
        // deliver more than ~20 CU/s, well under 50% of the 100 CU/s floor target.
        WorkerPool pool = new WorkerPool(slow, bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> i.getAndIncrement(), 1, 1, System.nanoTime());

        // warmNanos=0 and a minRampNanos far longer than this test's run: the controller
        // reaches RAMPING on tick one and then holds at its 10% floor (100 CU/s) forever,
        // exactly the "ramp unable to advance" condition the STALL guard watches for.
        RampController ramp = new RampController(1000, 0.9, 60_000_000_000L, 0L, 10_000_000L);
        Duration tickInterval = Duration.ofMillis(20);
        PhaseRunner runner = new PhaseRunner("stall-test-phase", pool, bucket, meter, ramp,
            () -> 0.10, Duration.ofSeconds(60), Duration.ofSeconds(5), null, (byte) 0, (byte) 1,
            null, tickInterval);

        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(capturedOut, true));
            System.setErr(new PrintStream(capturedErr, true));
            runner.run();
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
            writer.close();
            bucket.close();
        }

        List<String> stallLines = Arrays.stream(capturedErr.toString().split("\\R"))
            .filter(l -> l.startsWith("STALL phase=stall-test-phase")).toList();

        assertThat(stallLines)
            .as("STALL should fire once achieved has sat below 50%% of target for more than "
                + "%d consecutive ticks with the ramp not yet HOLDING; full stderr:%n%s",
                PhaseRunner.STALL_THRESHOLD_TICKS, capturedErr)
            .isNotEmpty();
        for (String field : new String[] {
                "consecutiveTicks=", "target=", "achieved=", "bucketTokens=", "bucketCapacity=",
                "rampState=", "rampReason="}) {
            assertThat(stallLines.get(0)).as("STALL line missing field %s: %s", field, stallLines.get(0))
                .contains(field);
        }
        // The first STALL line must report a streak strictly greater than the threshold, not
        // an early or off-by-one trigger.
        String firstConsecutive = stallLines.get(0).replaceAll(".*consecutiveTicks=(\\d+).*", "$1");
        assertThat(Integer.parseInt(firstConsecutive)).isGreaterThan(PhaseRunner.STALL_THRESHOLD_TICKS);
    }

    /**
     * Task-17's real root cause (rescoped from the lost-work hypothesis, see
     * WorkerPoolExhaustionDrainTest): a read phase running against a mostly-empty table
     * (e.g. a stale resume checkpoint from a deleted-and-recreated table) is a validity
     * failure, not a performance quirk. A miss-heavy window must fail outright, and the
     * live per-tick MISS-RATE warning must fire well before the window even closes.
     */
    @Test
    void aHighMissRateFailsTheWindowAndWarnsLiveDuringTheTicks() throws Exception {
        Workload mostlyMisses = new Workload() {
            final AtomicInteger n = new AtomicInteger();
            public Outcome execute(int i) {
                // 1 hit for every 9 misses: 90% miss rate, far past the 1% threshold.
                boolean hit = n.incrementAndGet() % 10 == 0;
                return new Outcome(200_000L, 1.0, 1, 0, false, hit);
            }
            public double estimatedCapacityUnits(int i) { return 1.0; }
            public double maxCapacityUnits() { return 1.0; }
            public byte phaseId() { return 0; }
        };
        TokenBucket bucket = new TokenBucket(4000, 0.1);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(1_000_000_000L, 100);
        RecordWriter writer = new RecordWriter(tmp.resolve("p8.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        WorkerPool pool = new WorkerPool(mostlyMisses, bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> i.getAndIncrement(), 8, 32, System.nanoTime());

        RampController ramp = new RampController(4000, 0.9, 400_000_000L, 200_000_000L, 50_000_000L);
        PrintStream realErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        PhaseRunner.WindowResult result;
        try {
            System.setErr(new PrintStream(capturedErr, true));
            PhaseRunner runner = new PhaseRunner("miss-rate-test", pool, bucket, meter, ramp,
                () -> 0.10, Duration.ofSeconds(30), Duration.ofSeconds(60),
                tmp.resolve("p8.hlog"), (byte) 0, (byte) 1);
            result = runner.run();
        } finally {
            System.setErr(realErr);
            writer.close();
            bucket.close();
        }

        assertThat(result.valid())
            .as("a window with a ~90%% miss rate must never be reported valid")
            .isFalse();
        assertThat(result.violations())
            .as("violations: %s", result.violations())
            .anySatisfy(v -> assertThat(v).contains("miss rate"));

        String logged = capturedErr.toString();
        assertThat(logged)
            .as("the live per-tick guard must also fire, not only the end-of-window check: %s", logged)
            .contains("MISS-RATE phase=miss-rate-test");
    }

    /**
     * Task-17, rescoped: the lost-work bug is real but was NOT the cause of the field
     * incident (see WorkerPoolExhaustionDrainTest's javadoc for the full story). This is the
     * end-to-end proof that PhaseRunner.run() itself -- not just WorkerPool.drain() in
     * isolation -- never returns from its exhaustion branch while a worker still holds an
     * already-dequeued index it has not executed.
     *
     * itemCount (40) comfortably exceeds threads (8) and the bucket's initial burst (20 CU
     * at 1 CU/request), so roughly half the batch is gated on the bucket's continuous refill
     * at 40 CU/s -- a real, bounded wait, not an instant grant -- while workload.execute()
     * itself is effectively instantaneous. exhaustion trips (some worker's OWN loop-back
     * next() call returns -1) well before every worker still queued on acquire() has been
     * paid, which is exactly the window this test needs: a fast, 5ms tickInterval so
     * PhaseRunner reacts to exhausted() quickly, while completion is still gated by the
     * bucket. Mutating PhaseRunner to skip its pool.drain(...) call (see the task's report)
     * makes this test fail with completed() short of itemCount -- proving the assertion is
     * not vacuous.
     */
    @Test
    void exhaustionNeverReturnsUntilEveryHandedOutIndexHasCompleted() throws Exception {
        int itemCount = 40;
        Workload instantaneous = new Workload() {
            public Outcome execute(int i) { return new Outcome(1_000L, 1.0, 1, 0, false); }
            public double estimatedCapacityUnits(int i) { return 1.0; }
            public double maxCapacityUnits() { return 1.0; }
            public byte phaseId() { return 0; }
        };
        TokenBucket bucket = new TokenBucket(40.0, 0.5, 1.0);
        bucket.start();
        CapacityMeter meter = new CapacityMeter(10_000_000_000L, 100);
        RecordWriter writer = new RecordWriter(tmp.resolve("p9.bin.gz"), 128, 512);
        writer.start();
        AtomicInteger i = new AtomicInteger();
        WorkerPool pool = new WorkerPool(instantaneous, bucket, meter,
            new PhaseStats("ramp", 1_000_000L), writer,
            () -> { int v = i.getAndIncrement(); return v < itemCount ? v : -1; }, 8, 8,
            System.nanoTime());

        // ceiling=400 -> floor target 40 CU/s (FLOOR_FRACTION=0.10), pinned there for the
        // whole test by a minRampNanos/advanceIntervalNanos far longer than it can possibly
        // run: HOLDING must never be reached, so the ONLY way this phase ends is exhaustion.
        RampController ramp = new RampController(400, 0.9, 60_000_000_000L, 0L, 60_000_000_000L);
        PhaseRunner runner = new PhaseRunner("exhaustion-fix-test", pool, bucket, meter, ramp,
            () -> 0.10, Duration.ofSeconds(60), Duration.ofSeconds(10), null, (byte) 0, (byte) 1,
            null, Duration.ofMillis(5));

        PhaseRunner.WindowResult result = assertTimeoutPreemptively(Duration.ofSeconds(15),
            runner::run);
        writer.close();
        bucket.close();

        assertThat(result.violations())
            .as("this test must exercise the exhaustion branch, not the ramp-deadline one: %s",
                result.violations())
            .anySatisfy(v -> assertThat(v).contains("work exhausted"));
        assertThat(pool.completed())
            .as("every handed-out index must complete before the exhaustion branch returns -- "
                + "none may be abandoned by an interrupt")
            .isEqualTo((long) itemCount);
        assertThat(result.stats().raw().count()).isEqualTo((long) itemCount);
    }
}
