package com.rickh.ddblat.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rickh.ddblat.metrics.PhaseStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SummaryWriterTest {

    @TempDir Path tmp;

    @Test
    void writesEveryPercentileAndValidityFieldAsReadableJson() throws Exception {
        PhaseStats s = new PhaseStats("R-B", 1_000_000L);
        for (int i = 1; i <= 10_000; i++) s.record(i * 1_000L);
        s.recordThrottle();
        s.recordHit();
        s.recordMiss();

        Path out = tmp.resolve("summary.json");
        SummaryWriter.write(out, List.of(new SummaryWriter.PhaseSummary(
            "R-B", s.raw(), s.corrected(), s.throttles(), s.retries(), s.hits(), s.misses(),
            35_900.0, false, List.of("throttles inside window: 1"))));

        JsonNode root = new ObjectMapper().readTree(out.toFile());
        JsonNode phase = root.get("phases").get(0);
        assertThat(phase.get("name").asText()).isEqualTo("R-B");
        assertThat(phase.get("valid").asBoolean()).isFalse();
        assertThat(phase.get("violations").get(0).asText()).contains("throttles");
        assertThat(phase.get("throttles").asLong()).isEqualTo(1L);
        assertThat(phase.get("hits").asLong()).isEqualTo(1L);
        assertThat(phase.get("misses").asLong()).isEqualTo(1L);
        assertThat(phase.get("achievedUnitsPerSec").asDouble()).isEqualTo(35_900.0);
        for (String p : List.of("p50", "p90", "p99", "p999", "p9999", "max", "mean", "count")) {
            assertThat(phase.get("raw").has(p)).as("raw.%s", p).isTrue();
            assertThat(phase.get("corrected").has(p)).as("corrected.%s", p).isTrue();
        }
    }

    @Test
    void multiplePhasesAreWrittenInOrder() throws Exception {
        PhaseStats a = new PhaseStats("LOAD", 1_000_000L);
        a.record(5_000_000L);
        Path out = tmp.resolve("s2.json");
        SummaryWriter.write(out, List.of(
            new SummaryWriter.PhaseSummary("LOAD", a.raw(), a.corrected(), 0, 0, 0, 0, 36_000, true, List.of()),
            new SummaryWriter.PhaseSummary("R-A", a.raw(), a.corrected(), 0, 0, 0, 0, 35_800, true, List.of())));
        JsonNode root = new ObjectMapper().readTree(out.toFile());
        assertThat(root.get("phases")).hasSize(2);
        assertThat(root.get("phases").get(0).get("name").asText()).isEqualTo("LOAD");
        assertThat(root.get("phases").get(1).get("name").asText()).isEqualTo("R-A");
    }
}
