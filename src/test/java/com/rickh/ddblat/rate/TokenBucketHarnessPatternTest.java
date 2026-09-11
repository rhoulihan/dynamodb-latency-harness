package com.rickh.ddblat.rate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIAGNOSTIC/reproduction harness for the task-17 field incident: the real read phase
 * delivered ~14 CU/s against a 100 CU/s floor target with all 8 workers parked in {@link
 * TokenBucket#acquire}, while {@link TokenBucketThroughputTest} passes at the exact same
 * (rate, cost, threads) tuple.
 *
 * The one structural difference between that passing test and the real harness: the harness
 * calls {@link TokenBucket#setRate} once per second on a SEPARATE controller thread, exactly
 * as {@link com.rickh.ddblat.phase.PhaseRunner}'s 1 Hz tick does -- including calling it
 * with the SAME value repeatedly while the ramp is holding (WARM, or blocked in RAMPING).
 * This class reproduces that exact pattern: N worker threads doing acquire+short-work-delay
 * in a tight loop, plus a controller thread ticking setRate(target) at 1 Hz, for ~10s, then
 * measures achieved CU/s the same way the field incident was measured (units granted /
 * wall-clock elapsed).
 */
class TokenBucketHarnessPatternTest {

    private static final double ACHIEVED_FRACTION = 0.90;
    private static final double BURST_SECONDS = 0.1;      // matches Main.runPhase
    private static final double CAPACITY_FLOOR_REQUESTS = 4.0;  // matches Main.CAPACITY_FLOOR_REQUESTS
    private static final long WORK_DELAY_NANOS = 2_000_000L;    // ~2ms, like the measured 1.6ms GetItem
    private static final long CONTROLLER_TICK_MILLIS = 1_000L;  // PhaseRunner's 1 Hz tick

    private record Result(double achievedUnitsPerSecond, long granted, double elapsedSeconds) {}

    /**
     * @param callSetRate   a controller thread ticks {@code bucket.setRate(unitsPerSecond)}
     *                      once per second, the SAME value every tick -- exactly how
     *                      PhaseRunner drives a held ramp target -- when true; when false, no
     *                      controller thread runs at all (matches TokenBucketThroughputTest).
     * @param withWorkDelay each worker parks ~2ms after every grant, like a real GetItem RTT,
     *                      when true; when false, workers loop back-to-back with no delay
     *                      (matches TokenBucketThroughputTest).
     * @param startFull     bucket starts at full capacity (the constructor's own behavior)
     *                      when true; when false, the initial burst is drained before workers
     *                      start so only steady-state refill behavior is measured.
     */
    private static Result run(double unitsPerSecond, double requestCost, int threads,
            double runSeconds, boolean callSetRate, boolean withWorkDelay, boolean startFull)
            throws InterruptedException {
        double minCapacityUnits = requestCost * CAPACITY_FLOOR_REQUESTS;
        try (TokenBucket bucket = new TokenBucket(unitsPerSecond, BURST_SECONDS, minCapacityUnits)) {
            if (!startFull) {
                while (bucket.tryAcquire(requestCost)) {
                    // drain the constructor's initial full-bucket burst
                }
            }
            bucket.start();

            DoubleAdder granted = new DoubleAdder();
            AtomicBoolean stop = new AtomicBoolean(false);
            CountDownLatch go = new CountDownLatch(1);

            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> {
                    try {
                        go.await();
                        while (!stop.get()) {
                            bucket.acquire(requestCost);
                            granted.add(requestCost);
                            if (withWorkDelay) {
                                LockSupport.parkNanos(WORK_DELAY_NANOS);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "harness-worker-" + i);
                workers[i].setDaemon(true);
            }

            Thread controller = null;
            if (callSetRate) {
                controller = new Thread(() -> {
                    try {
                        go.await();
                        // PhaseRunner ticks bucket.setRate(target) once per second, holding the
                        // SAME target across many ticks whenever the ramp cannot advance -- this
                        // is that exact call pattern, not a one-shot setRate before the run.
                        while (!stop.get()) {
                            bucket.setRate(unitsPerSecond);
                            Thread.sleep(CONTROLLER_TICK_MILLIS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "harness-controller");
                controller.setDaemon(true);
            }

            long t0 = System.nanoTime();
            for (Thread w : workers) w.start();
            if (controller != null) controller.start();
            go.countDown();

            Thread.sleep((long) (runSeconds * 1000));
            stop.set(true);
            for (Thread w : workers) w.interrupt();
            if (controller != null) controller.interrupt();
            for (Thread w : workers) w.join();
            if (controller != null) controller.join();
            double elapsedSeconds = (System.nanoTime() - t0) / 1_000_000_000.0;

            return new Result(granted.sum() / elapsedSeconds, granted.longValue(), elapsedSeconds);
        }
    }

    /**
     * The reproduction proper. Same three tuples the live incident and its floor cover: the
     * exact failing configuration (100 CU/s, 13 CU/request, 8 threads), production's LOAD ramp
     * floor (4,000/50/32), and production's 90%-of-ceiling read/write target (36,000/50/32).
     */
    @ParameterizedTest(name = "{index}: {0} CU/s, {1} CU/request, {2} threads")
    @CsvSource({
        "100,    13,   8",     // exact failing configuration from the live run
        "36000,  50,  32",     // production: 90% of a 40,000-unit ceiling
        "4000,   50,  32",     // production: LOAD ramp floor
    })
    void harnessPatternSustainsAtLeastNinetyPercentOfNominal(
            double unitsPerSecond, double requestCost, int threads) throws Exception {
        Result r = run(unitsPerSecond, requestCost, threads, 10.0,
            /* callSetRate= */ true, /* withWorkDelay= */ true, /* startFull= */ true);

        System.out.printf(
            "HARNESS-PATTERN %.0f CU/s x %.1f CU/req x %d threads -> achieved %.1f CU/s "
            + "(%.1f%% of nominal) over %.2fs, %d units granted%n",
            unitsPerSecond, requestCost, threads, r.achievedUnitsPerSecond(),
            100 * r.achievedUnitsPerSecond() / unitsPerSecond, r.elapsedSeconds(), r.granted());

        assertThat(r.achievedUnitsPerSecond())
            .as("%.1f CU/s achieved against a %.1f CU/s nominal target under the harness's real "
                + "usage pattern (setRate ticked at 1 Hz, %.1f CU/request, %d threads, ~2ms "
                + "simulated work delay per request)",
                r.achievedUnitsPerSecond(), unitsPerSecond, requestCost, threads)
            .isGreaterThanOrEqualTo(ACHIEVED_FRACTION * unitsPerSecond);
    }

    /**
     * Isolation matrix at the exact failing configuration (100 CU/s, 13 CU/request, 8 threads):
     * toggles setRate-ticking, work delay, and initial fill independently and prints achieved
     * CU/s for each of the 8 combinations, so the report can say plainly which factor(s) the
     * collapse depends on. Every cell is also asserted at the same 90%-of-nominal bar as the
     * main reproduction test: on this hardware none of the 8 combinations reproduces the field
     * collapse, so this doubles as a regression guard against any of the three factors (setRate
     * ticking, work delay, initial fill state) breaking throughput in isolation later.
     */
    @Test
    void isolationMatrixAtTheFailingConfiguration() throws Exception {
        double unitsPerSecond = 100, requestCost = 13;
        int threads = 8;
        double runSeconds = 5.0;

        StringBuilder report = new StringBuilder("\nISOLATION MATRIX (100 CU/s, 13 CU/req, 8 threads, "
            + runSeconds + "s each):\n");
        StringBuilder failures = new StringBuilder();
        for (boolean callSetRate : new boolean[] {false, true}) {
            for (boolean withWorkDelay : new boolean[] {false, true}) {
                for (boolean startFull : new boolean[] {true, false}) {
                    Result r = run(unitsPerSecond, requestCost, threads, runSeconds,
                        callSetRate, withWorkDelay, startFull);
                    double pctOfNominal = 100 * r.achievedUnitsPerSecond() / unitsPerSecond;
                    report.append(String.format(
                        "  setRate=%-5s workDelay=%-5s startFull=%-5s -> achieved %7.1f CU/s (%5.1f%% of nominal)%n",
                        callSetRate, withWorkDelay, startFull, r.achievedUnitsPerSecond(), pctOfNominal));
                    if (r.achievedUnitsPerSecond() < ACHIEVED_FRACTION * unitsPerSecond) {
                        failures.append(String.format(
                            "setRate=%s workDelay=%s startFull=%s: achieved %.1f CU/s (%.1f%% of nominal);%n",
                            callSetRate, withWorkDelay, startFull, r.achievedUnitsPerSecond(), pctOfNominal));
                    }
                }
            }
        }
        System.out.println(report);
        assertThat(failures.toString())
            .as("cells below 90%% of nominal in the isolation matrix:%n%s", failures)
            .isEmpty();
    }
}
