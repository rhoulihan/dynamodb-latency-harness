package com.rickh.ddblat.report;

import com.rickh.ddblat.metrics.PhaseStats;
import org.HdrHistogram.EncodableHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class IntervalLoggerTest {

    @TempDir Path tmp;

    @Test
    void writesReadableHistogramIntervalsWithoutLosingSamples() throws Exception {
        PhaseStats stats = new PhaseStats("R-B", 1_000_000L);
        Path hlog = tmp.resolve("R-B.hlog");

        try (IntervalLogger logger = new IntervalLogger(
                hlog, stats::rawHistogram, Duration.ofMillis(50))) {
            logger.start();
            for (int round = 0; round < 4; round++) {
                for (int i = 1; i <= 500; i++) stats.record(i * 10_000L);
                Thread.sleep(80);
            }
        }

        long intervals = 0, total = 0;
        try (HistogramLogReader reader = new HistogramLogReader(hlog.toFile())) {
            EncodableHistogram h;
            while ((h = reader.nextIntervalHistogram()) != null) {
                intervals++;
                total += ((Histogram) h).getTotalCount();
            }
        }
        assertThat(intervals).as("interval histograms written").isGreaterThanOrEqualTo(2L);
        assertThat(total).as("no samples lost across interval boundaries").isEqualTo(2_000L);
    }

    @Test
    void anEmptyPhaseProducesAParseableLogWithNoIntervals() throws Exception {
        PhaseStats stats = new PhaseStats("idle", 1_000_000L);
        Path hlog = tmp.resolve("idle.hlog");
        try (IntervalLogger logger = new IntervalLogger(
                hlog, stats::rawHistogram, Duration.ofMillis(50))) {
            logger.start();
            Thread.sleep(120);
        }
        long intervals = 0;
        try (HistogramLogReader reader = new HistogramLogReader(hlog.toFile())) {
            while (reader.nextIntervalHistogram() != null) intervals++;
        }
        assertThat(intervals).isZero();
    }
}
