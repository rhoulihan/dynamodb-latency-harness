package com.rickh.ddblat.aws;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CloudWatchCrossCheckTest {

    @Test
    void agreementWithinTwoPercentPasses() {
        var r = CloudWatchCrossCheck.evaluate(36_000, 35_500, 4_200, 0.02);
        assertThat(r.deltaFraction()).isLessThan(0.02);
        assertThat(r.withinTolerance()).isTrue();
    }

    @Test
    void disagreementBeyondTwoPercentFails() {
        var r = CloudWatchCrossCheck.evaluate(36_000, 30_000, 4_200, 0.02);
        assertThat(r.withinTolerance()).isFalse();
    }

    @Test
    void deltaIsSymmetricSoOverAndUnderCountingBothFail() {
        var over = CloudWatchCrossCheck.evaluate(30_000, 36_000, 4_200, 0.02);
        assertThat(over.withinTolerance()).isFalse();
    }

    @Test
    void zeroCloudWatchUnitsIsTreatedAsAFailureNotADivideByZero() {
        var r = CloudWatchCrossCheck.evaluate(0, 36_000, 4_200, 0.02);
        assertThat(r.withinTolerance()).isFalse();
        assertThat(Double.isFinite(r.deltaFraction())).isTrue();
    }
}
