package com.rickh.ddblat.rate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RampControllerTest {

    private static final double CEILING  = 40_000.0;
    private static final double FRACTION = 0.90;              // max target 36,000 CU/s
    private static final long   SEC      = 1_000_000_000L;
    private static final long   MIN_RAMP = 300 * SEC;         // 5 minutes
    private static final long   ADVANCE  = 30 * SEC;          // production advance interval

    private static RampController controller(long warmNanos) {
        return new RampController(CEILING, FRACTION, MIN_RAMP, warmNanos, ADVANCE);
    }

    @Test
    void warmPeriodHoldsTenPercentOfCeiling() {
        RampController c = controller(60 * SEC);
        assertThat(c.tick(0, 4_000, 0, 0.10, false, 0).targetUnitsPerSec()).isEqualTo(4_000.0);
        assertThat(c.tick(30 * SEC, 4_000, 0, 0.10, false, 0).state()).isEqualTo(RampController.State.WARM);

        RampController.Decision d = c.tick(59 * SEC, 4_000, 0, 0.10, false, 0);
        assertThat(d.targetUnitsPerSec()).isEqualTo(4_000.0);
        assertThat(d.state()).isEqualTo(RampController.State.WARM);
        assertThat(d.reason()).contains("warming");

        assertThat(c.tick(60 * SEC, 4_000, 0, 0.10, false, 0).state())
            .isEqualTo(RampController.State.RAMPING);
    }

    @Test
    void advancesTenPointsEveryThirtySecondsWhenAllThreeConditionsHold() {
        RampController c = controller(0);
        RampController.Decision d = c.tick(0, 4_000, 0, 0.10, false, 0);
        assertThat(d.state()).isEqualTo(RampController.State.RAMPING);
        assertThat(d.targetUnitsPerSec()).isEqualTo(4_000.0);

        assertThat(c.tick(29 * SEC, 4_000, 0, 0.10, false, 0).targetUnitsPerSec()).isEqualTo(4_000.0);

        d = c.tick(30 * SEC, 4_000, 0, 0.10, false, 0);
        assertThat(d.targetUnitsPerSec()).isEqualTo(8_000.0);
        assertThat(d.reason()).contains("advanced");

        assertThat(c.tick(60 * SEC, 8_000, 0, 0.10, false, 0).targetUnitsPerSec()).isEqualTo(12_000.0);
    }

    @Test
    void achievedBelowNinetyFivePercentBlocksAdvancementAndIsNamed() {
        RampController c = controller(0);
        c.tick(0, 4_000, 0, 0.10, false, 0);
        RampController.Decision d = c.tick(30 * SEC, 3_700, 0, 0.10, false, 0);   // 92.5% of target
        assertThat(d.targetUnitsPerSec()).isEqualTo(4_000.0);
        assertThat(d.state()).isEqualTo(RampController.State.RAMPING);
        assertThat(d.reason()).contains("achieved").contains("95%");
    }

    @Test
    void clientCpuAtOrAboveSeventyPercentBlocksAdvancementAndIsNamed() {
        RampController c = controller(0);
        c.tick(0, 4_000, 0, 0.10, false, 0);
        RampController.Decision d = c.tick(30 * SEC, 4_000, 0, 0.71, false, 0);
        assertThat(d.targetUnitsPerSec()).isEqualTo(4_000.0);
        assertThat(d.reason()).contains("client cpu");
    }

    @Test
    void aThrottleDropsTheTargetTenPointsAndFreezesAdvancementForSixtySeconds() {
        RampController c = controller(0);
        c.tick(0, 4_000, 0, 0.10, false, 0);
        assertThat(c.tick(30 * SEC, 4_000, 0, 0.10, false, 0).targetUnitsPerSec()).isEqualTo(8_000.0);
        assertThat(c.tick(60 * SEC, 8_000, 0, 0.10, false, 0).targetUnitsPerSec()).isEqualTo(12_000.0);

        RampController.Decision d = c.tick(90 * SEC, 12_000, 1, 0.10, false, 0);
        assertThat(d.targetUnitsPerSec()).isEqualTo(8_000.0);
        assertThat(d.reason()).contains("throttle");

        // 30 s later every condition holds, but the 60 s freeze is still running
        d = c.tick(120 * SEC, 8_000, 0, 0.10, false, 0);
        assertThat(d.targetUnitsPerSec()).isEqualTo(8_000.0);
        assertThat(d.reason()).contains("frozen");

        // freeze expired at 150 s
        d = c.tick(151 * SEC, 8_000, 0, 0.10, false, 0);
        assertThat(d.targetUnitsPerSec()).isEqualTo(12_000.0);
    }

    @Test
    void targetStaysInsideTheTenToNinetyPercentBand() {
        RampController c = controller(0);
        double achieved = 4_000;
        RampController.Decision d = c.tick(0, achieved, 0, 0.10, false, 0);
        for (int i = 1; i <= 20; i++) {
            d = c.tick(i * 30 * SEC, achieved, 0, 0.10, false, 0);
            achieved = d.targetUnitsPerSec();
            assertThat(d.targetUnitsPerSec()).isLessThanOrEqualTo(36_000.0);
        }
        assertThat(d.targetUnitsPerSec()).isEqualTo(36_000.0);

        for (int i = 21; i <= 40; i++) {                       // 20 throttled windows in a row
            d = c.tick(i * 30 * SEC, 1_000, 1, 0.10, false, 0);
        }
        assertThat(d.targetUnitsPerSec()).isEqualTo(4_000.0);  // floors at 10% of ceiling
    }

    @Test
    void doesNotEnterHoldingBeforeTheMinimumRampEvenWhenTheTargetIsReachedEarly() {
        RampController c = controller(10 * SEC);
        double achieved = 4_000;
        RampController.Decision d = c.tick(0, achieved, 0, 0.10, false, 0);
        assertThat(d.state()).isEqualTo(RampController.State.WARM);

        d = c.tick(10 * SEC, achieved, 0, 0.10, false, 0);     // WARM -> RAMPING
        assertThat(d.state()).isEqualTo(RampController.State.RAMPING);

        for (int i = 1; i <= 8; i++) {                          // 8 advances: 4,000 -> 36,000
            d = c.tick(10 * SEC + i * 30 * SEC, achieved, 0, 0.10, false, 0);
            achieved = d.targetUnitsPerSec();
        }
        assertThat(d.targetUnitsPerSec()).isEqualTo(36_000.0);  // ceiling reached at t = 250 s
        assertThat(d.state()).isEqualTo(RampController.State.RAMPING);

        d = c.tick(299 * SEC, achieved, 0, 0.10, false, 0);     // JIT gate: 1 s short of 5 min
        assertThat(d.state()).isEqualTo(RampController.State.RAMPING);
        assertThat(d.targetUnitsPerSec()).isEqualTo(36_000.0);
        assertThat(d.reason()).contains("minimum ramp");

        d = c.tick(300 * SEC, achieved, 0, 0.10, false, 0);
        assertThat(d.state()).isEqualTo(RampController.State.HOLDING);
        assertThat(c.state()).isEqualTo(RampController.State.HOLDING);
    }

    @Test
    void addsSixteenThreadsOnlyWhenTheBucketSitsAtCapWithTheTargetUnmet() {
        RampController c = controller(0);
        c.tick(0, 4_000, 0, 0.10, false, 0);
        assertThat(c.tick(1 * SEC, 1_000, 0, 0.10, true, 1_500_000_000L).threadDelta()).isEqualTo(16);
        assertThat(c.tick(2 * SEC, 1_000, 0, 0.10, true,   500_000_000L).threadDelta()).isZero();
        assertThat(c.tick(3 * SEC, 1_000, 0, 0.10, false, 1_500_000_000L).threadDelta()).isZero();
        assertThat(c.tick(4 * SEC, 4_000, 0, 0.10, true,  1_500_000_000L).threadDelta()).isZero();
    }
}
