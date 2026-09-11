package com.rickh.ddblat.rate;

/**
 * Ramp state machine: WARM -> RAMPING -> HOLDING.
 *
 * A pure function of its arguments. No threads, no sleeping, no clock reads -- tick()
 * is handed nowNanos, so a 5-minute ramp is verifiable in microseconds and the run's
 * behaviour is reproducible from a log of its inputs. Construction time is taken from
 * the first tick for the same reason.
 *
 * The controller does not know how many worker threads exist. threadDelta is advisory
 * (+16 or 0); the caller applies it and clamps the pool at 256.
 *
 * Not thread-safe: one control loop owns it.
 */
public final class RampController {

    public enum State { WARM, RAMPING, HOLDING }

    public record Decision(double targetUnitsPerSec, int threadDelta, State state, String reason) {}

    private static final long   FREEZE_NANOS           = 60_000_000_000L;   // 60 s after a throttle
    private static final long   AT_CAP_TRIGGER_NANOS   = 1_000_000_000L;    // 1 s at cap
    private static final double STEP_FRACTION          = 0.10;
    private static final double FLOOR_FRACTION         = 0.10;
    private static final double ACHIEVED_FRACTION      = 0.95;
    private static final double CPU_LIMIT              = 0.70;
    private static final int    THREAD_STEP            = 16;
    private static final double EPS                    = 1e-9;

    private final double ceiling;
    private final double targetFraction;
    private final double maxTarget;
    private final double minTarget;
    private final double step;
    private final long minRampNanos;
    private final long warmNanos;

    private boolean started;
    private long startNanos;
    private long lastAdvanceNanos;
    private final long advanceIntervalNanos;
    private long freezeUntilNanos = Long.MIN_VALUE;
    private double target;
    private State state = State.WARM;

    /**
     * advanceIntervalNanos is a parameter, not a constant, so a test can compress the
     * ramp timeline. With it hardcoded at 30 s, any test that drives a real PhaseRunner
     * needs 8 advances x 30 s = 240 s of wall clock just to walk 10% -> 90%, which is
     * not a TDD loop. Production passes 30_000_000_000L.
     */
    public RampController(double ceilingUnitsPerSec, double targetFraction,
                          long minRampNanos, long warmNanos, long advanceIntervalNanos) {
        if (ceilingUnitsPerSec <= 0) {
            throw new IllegalArgumentException("ceilingUnitsPerSec must be > 0, got " + ceilingUnitsPerSec);
        }
        if (targetFraction <= 0 || targetFraction > 1) {
            throw new IllegalArgumentException("targetFraction must be in (0,1], got " + targetFraction);
        }
        if (minRampNanos < 0 || warmNanos < 0) {
            throw new IllegalArgumentException("minRampNanos and warmNanos must be >= 0");
        }
        if (advanceIntervalNanos <= 0) {
            throw new IllegalArgumentException(
                "advanceIntervalNanos must be > 0, got " + advanceIntervalNanos);
        }
        this.ceiling              = ceilingUnitsPerSec;
        this.targetFraction       = targetFraction;
        this.maxTarget            = ceilingUnitsPerSec * targetFraction;
        this.minTarget            = ceilingUnitsPerSec * FLOOR_FRACTION;
        this.step                 = ceilingUnitsPerSec * STEP_FRACTION;
        this.minRampNanos         = minRampNanos;
        this.warmNanos            = warmNanos;
        this.advanceIntervalNanos = advanceIntervalNanos;
        this.target               = this.minTarget;
    }

    public State state() {
        return state;
    }

    /** The constructor's ceilingUnitsPerSec, for phase-start logging. */
    public double ceiling() {
        return ceiling;
    }

    /** The constructor's targetFraction, for phase-start logging. */
    public double targetFraction() {
        return targetFraction;
    }

    /** ceiling * FLOOR_FRACTION -- the lowest target the ramp will ever hold. */
    public double minTarget() {
        return minTarget;
    }

    /** ceiling * targetFraction -- the highest target the ramp will ever advance to. */
    public double maxTarget() {
        return maxTarget;
    }

    public Decision tick(long nowNanos,
                         double achievedUnitsPerSec,
                         int throttlesInLastWindow,
                         double clientCpuFraction,
                         boolean bucketAtCap,
                         long atCapNanos) {

        if (!started) {
            started = true;
            startNanos = nowNanos;
            lastAdvanceNanos = nowNanos;
            target = minTarget;
            state = State.WARM;
        }
        long sinceStart = nowNanos - startNanos;

        // Client saturation is judged against the target in force when the sample was taken.
        int threadDelta = (bucketAtCap
                && atCapNanos > AT_CAP_TRIGGER_NANOS
                && achievedUnitsPerSec < target) ? THREAD_STEP : 0;

        // A throttle outranks everything, including the warm-up and the hold.
        if (throttlesInLastWindow > 0) {
            target = Math.max(minTarget, target - step);
            freezeUntilNanos = nowNanos + FREEZE_NANOS;
            lastAdvanceNanos = nowNanos;
            state = (sinceStart < warmNanos) ? State.WARM : State.RAMPING;
            return new Decision(target, threadDelta, state,
                "throttled: " + throttlesInLastWindow + " in the last window; target dropped to "
                    + fmt(target) + " CU/s and advancement frozen for 60s");
        }

        if (sinceStart < warmNanos) {
            state = State.WARM;
            return new Decision(target, threadDelta, state,
                "warming: holding " + fmt(target) + " CU/s (10% of ceiling " + fmt(ceiling)
                    + ") for the first " + (warmNanos / 1_000_000_000L) + "s");
        }

        if (state == State.WARM) {                 // first tick past the warm-up
            state = State.RAMPING;
            lastAdvanceNanos = nowNanos;
        }

        if (state == State.HOLDING) {
            return new Decision(target, threadDelta, state,
                "holding at " + fmt(target) + " CU/s");
        }

        if (nowNanos < freezeUntilNanos) {
            return new Decision(target, threadDelta, state,
                "frozen after throttle: " + ((freezeUntilNanos - nowNanos) / 1_000_000L)
                    + "ms of the 60s freeze remain");
        }

        if (target >= maxTarget - EPS) {
            // The JIT gate: capacity targets are met in ~4 minutes, but no measurement
            // window opens until the minimum ramp has elapsed and compilation has quiesced.
            if (sinceStart >= minRampNanos) {
                state = State.HOLDING;
                return new Decision(target, threadDelta, state,
                    "holding: target at " + fmt(target) + " CU/s and the minimum ramp has elapsed;"
                        + " a measurement window may open");
            }
            return new Decision(target, threadDelta, state,
                "target at " + fmt(target) + " CU/s but the minimum ramp has not elapsed (JIT gate): "
                    + ((minRampNanos - sinceStart) / 1_000_000_000L) + "s remain");
        }

        if (nowNanos - lastAdvanceNanos < advanceIntervalNanos) {
            return new Decision(target, threadDelta, state,
                "waiting for the advance interval: "
                    + ((advanceIntervalNanos - (nowNanos - lastAdvanceNanos)) / 1_000_000L) + "ms remain");
        }

        boolean throughputOk = achievedUnitsPerSec >= ACHIEVED_FRACTION * target;
        boolean cpuOk = clientCpuFraction < CPU_LIMIT;

        if (throughputOk && cpuOk) {
            target = Math.min(maxTarget, target + step);
            lastAdvanceNanos = nowNanos;
            return new Decision(target, threadDelta, state,
                "advanced to " + fmt(target) + " CU/s: achieved >= 95% of target, zero throttles,"
                    + " client cpu below 0.70");
        }

        StringBuilder sb = new StringBuilder(128).append("blocked:");
        if (!throughputOk) {
            sb.append(" achieved ").append(fmt(achievedUnitsPerSec))
              .append(" CU/s is below 95% of target ").append(fmt(target)).append(" CU/s;");
        }
        if (!cpuOk) {
            sb.append(" client cpu ").append(fmt(clientCpuFraction * 100))
              .append("% is at or above the 70% limit;");
        }
        return new Decision(target, threadDelta, state, sb.toString());
    }

    private static String fmt(double v) {
        return Long.toString(Math.round(v));
    }
}
