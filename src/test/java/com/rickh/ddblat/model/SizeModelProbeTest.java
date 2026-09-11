package com.rickh.ddblat.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the pure comparison at the heart of {@link SizeModelProbe}: the abort gate
 * that stands between a wrong item-size model and 118 GiB of writes against it. This is
 * deliberately isolated from {@link SizeModelProbe#validate} so the 0.5 WCU tolerance can be
 * pinned down without needing a real (or mocked) DynamoDB PutItem call.
 *
 * The same isolation applies to the audit-trail additions below: {@link SizeModelProbe#writeReport}
 * and {@link SizeModelProbe#runValidation} are tested directly (a canned
 * {@link SizeModelProbe.BilledCapacitySource} stands in for the real PutItem round trip), rather
 * than through {@link SizeModelProbe#validate}, which needs a real {@code DynamoDbClient}.
 */
class SizeModelProbeTest {

    @Test
    void anExactBilledMatchPasses() {
        assertThatCode(() -> SizeModelProbe.checkBilled(7, 51_200, 50, 50.0))
            .doesNotThrowAnyException();
    }

    @Test
    void fourTenthsOfAWcuOffStillPasses() {
        assertThatCode(() -> SizeModelProbe.checkBilled(7, 51_200, 50, 50.4))
            .doesNotThrowAnyException();
        assertThatCode(() -> SizeModelProbe.checkBilled(7, 51_200, 50, 49.6))
            .doesNotThrowAnyException();
    }

    @Test
    void sixTenthsOfAWcuOffThrows() {
        assertThatThrownBy(() -> SizeModelProbe.checkBilled(7, 51_200, 50, 50.6))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLargeMismatchThrows() {
        assertThatThrownBy(() -> SizeModelProbe.checkBilled(3, 51_200, 50, 5.0))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theMessageNamesTemplateIndexComputedBytesExpectedWcuAndBilledWcu() {
        assertThatThrownBy(() -> SizeModelProbe.checkBilled(42, 51_200, 50, 5.0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("template 42")
            .hasMessageContaining("51200 bytes")
            .hasMessageContaining("50 WCU")
            .hasMessageContaining("5.0");
    }

    // ---- size-model-probe.json: the auditable record of every computed-vs-billed pair -------

    @TempDir Path tmp;

    @Test
    void writeReportProducesEveryRowPlusASummaryWithCountMaxDeltaAndPass() throws Exception {
        List<SizeModelProbe.ProbeRecord> rows = List.of(
            new SizeModelProbe.ProbeRecord(0, 49_152, 48, 48.0, 0.0, true),
            new SizeModelProbe.ProbeRecord(85, 51_200, 50, 50.4, 0.4, true),
            new SizeModelProbe.ProbeRecord(255, 53_248, 52, 52.0, 0.0, true));

        Path out = tmp.resolve("size-model-probe.json");
        SizeModelProbe.writeReport(out, rows);

        JsonNode root = new ObjectMapper().readTree(out.toFile());
        JsonNode probes = root.get("probes");
        assertThat(probes).hasSize(3);
        JsonNode p1 = probes.get(1);
        assertThat(p1.get("templateIndex").asInt()).isEqualTo(85);
        assertThat(p1.get("modelBytes").asInt()).isEqualTo(51_200);
        assertThat(p1.get("expectedWcu").asInt()).isEqualTo(50);
        assertThat(p1.get("billedWcu").asDouble()).isEqualTo(50.4);
        assertThat(p1.get("delta").asDouble()).isEqualTo(0.4);
        assertThat(p1.get("ok").asBoolean()).isTrue();

        JsonNode summary = root.get("summary");
        assertThat(summary.get("count").asInt()).isEqualTo(3);
        assertThat(summary.get("maxAbsDelta").asDouble()).isEqualTo(0.4);
        assertThat(summary.get("pass").asBoolean()).isTrue();
    }

    @Test
    void writeReportSummaryFailsWhenAnyRowIsOutOfTolerance() throws Exception {
        List<SizeModelProbe.ProbeRecord> rows = List.of(
            new SizeModelProbe.ProbeRecord(0, 49_152, 48, 48.0, 0.0, true),
            new SizeModelProbe.ProbeRecord(1, 51_200, 50, 5.0, -45.0, false));

        Path out = tmp.resolve("size-model-probe.json");
        SizeModelProbe.writeReport(out, rows);

        JsonNode summary = new ObjectMapper().readTree(out.toFile()).get("summary");
        assertThat(summary.get("pass").asBoolean()).isFalse();
        assertThat(summary.get("maxAbsDelta").asDouble()).isEqualTo(45.0);
    }

    // ---- runValidation: the loop that logs, reports, and still aborts on a real mismatch -----

    private static SizeModelProbe.BilledCapacitySource matching() {
        return (j, item) -> {
            int modelBytes = ItemTemplateFactory.computeItemSizeBytes(item);
            return ItemSizeModel.writeCapacityUnits(modelBytes);
        };
    }

    @Test
    void runValidationWritesTheFullReportWhenEveryProbePasses() throws Exception {
        KeySpace keys = new KeySpace(256);
        ItemTemplateFactory factory = new ItemTemplateFactory(1L);

        assertThatCode(() -> SizeModelProbe.runValidation(4, keys, factory, tmp, matching()))
            .doesNotThrowAnyException();

        Path report = tmp.resolve("size-model-probe.json");
        assertThat(report).exists();
        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertThat(root.get("probes")).hasSize(4);
        assertThat(root.get("summary").get("count").asInt()).isEqualTo(4);
        assertThat(root.get("summary").get("pass").asBoolean()).isTrue();
    }

    @Test
    void runValidationStillAbortsOnADeliberateMismatchAndLeavesAPartialAuditTrail() throws Exception {
        KeySpace keys = new KeySpace(256);
        ItemTemplateFactory factory = new ItemTemplateFactory(1L);
        // Wrong on the very first probe (template 0): every other probe would match exactly.
        SizeModelProbe.BilledCapacitySource oneBadProbe = (j, item) -> {
            int modelBytes = ItemTemplateFactory.computeItemSizeBytes(item);
            int expected = ItemSizeModel.writeCapacityUnits(modelBytes);
            return j == 0 ? expected + 100.0 : expected;
        };

        assertThatThrownBy(() ->
            SizeModelProbe.runValidation(4, keys, factory, tmp, oneBadProbe))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("template 0");

        // The abort gate fired -- exactly as it always has -- but the audit trail for the one
        // probe examined before the abort must still exist.
        Path report = tmp.resolve("size-model-probe.json");
        assertThat(report).exists();
        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertThat(root.get("probes")).hasSize(1);
        assertThat(root.get("probes").get(0).get("ok").asBoolean()).isFalse();
        assertThat(root.get("summary").get("pass").asBoolean()).isFalse();
    }
}
