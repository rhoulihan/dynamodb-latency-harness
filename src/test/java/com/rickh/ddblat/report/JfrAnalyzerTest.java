package com.rickh.ddblat.report;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JfrAnalyzerTest {

    @Test
    void aSampleOverlapsAPauseWhenTheirIntervalsIntersect() {
        assertThat(JfrAnalyzer.overlaps(100, 200, 150, 250)).isTrue();   // pause starts mid-sample
        assertThat(JfrAnalyzer.overlaps(100, 200, 50, 150)).isTrue();    // pause ends mid-sample
        assertThat(JfrAnalyzer.overlaps(100, 200, 120, 130)).isTrue();   // pause inside sample
        assertThat(JfrAnalyzer.overlaps(100, 200, 50, 250)).isTrue();    // sample inside pause
    }

    @Test
    void disjointIntervalsDoNotOverlap() {
        assertThat(JfrAnalyzer.overlaps(100, 200, 200, 300)).isFalse();  // touching, not overlapping
        assertThat(JfrAnalyzer.overlaps(100, 200, 0, 100)).isFalse();
        assertThat(JfrAnalyzer.overlaps(100, 200, 500, 600)).isFalse();
    }

    @Test
    void aPauseReportWithNoTailSamplesReportsZeroFractionNotDivideByZero() {
        JfrAnalyzer.PauseReport r = new JfrAnalyzer.PauseReport(0, 0, 0, 0, 0, 0.0);
        assertThat(r.fractionOfTailDuringPause()).isZero();
    }
}
