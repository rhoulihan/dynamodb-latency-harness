package com.rickh.ddblat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The computed size model meets real billed capacity here, before 118 GiB is loaded against
 * a model that might be wrong. Validation granularity is 1 KiB -- the finest DynamoDB
 * exposes -- which is under 2% of a fixed 59 KiB item.
 *
 * This is the single most important validation in the project: it is what makes the claim
 * "2,097,152 items x 60,416 bytes = exactly 118 GiB" true rather than assumed. Every
 * computed-vs-billed pair is therefore kept (not just the pass/fail outcome), logged one line
 * per probe, and written to {@code size-model-probe.json} in the results directory so a report
 * can cite the real numbers instead of trusting a single summary line.
 */
public final class SizeModelProbe {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One probe's computed-vs-billed comparison. */
    record ProbeRecord(int templateIndex, int modelBytes, int expectedWcu, double billedWcu,
                      double delta, boolean ok) {}

    /** Supplies real billed WCU for one probe item; the only part of a probe that touches AWS. */
    @FunctionalInterface
    interface BilledCapacitySource {
        double billedWcuFor(int templateIndex, Map<String, AttributeValue> item);
    }

    private SizeModelProbe() {}

    public static void validate(DynamoDbClient ddb, String table, KeySpace keys,
                                ItemTemplateFactory factory, int probes, Path resultsDir) {
        runValidation(probes, keys, factory, resultsDir, (j, item) -> {
            PutItemResponse resp = ddb.putItem(r -> r
                .tableName(table).item(item)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
            return resp.consumedCapacity().capacityUnits();
        });
    }

    /**
     * Everything except the actual DynamoDB round trip, isolated so it is unit-testable
     * without AWS: {@code source} can be a canned function of template index in tests. Logs one
     * line per probe (bounded at ~100-128 lines for the production probe count -- not so chatty
     * it drowns the log) and always attempts to write the JSON report, on both the success and
     * the abort path, so a mismatch still leaves an auditable record of every probe examined so
     * far. The abort itself -- {@link #checkBilled}, thrown immediately on the first probe that
     * exceeds the 0.5 WCU tolerance -- is unchanged from before this reporting was added.
     */
    static void runValidation(int probes, KeySpace keys, ItemTemplateFactory factory,
                              Path resultsDir, BilledCapacitySource source) {
        List<ProbeRecord> rows = new ArrayList<>(probes);
        int stride = Math.max(1, ItemSizeModel.TEMPLATE_COUNT / probes);
        try {
            for (int j = 0; j < ItemSizeModel.TEMPLATE_COUNT; j += stride) {
                String key = keys.keyAt(j);
                var item = factory.itemFor(key, j);
                int modelBytes = ItemTemplateFactory.computeItemSizeBytes(item);
                int expectedWcu = ItemSizeModel.writeCapacityUnits(modelBytes);

                double billed = source.billedWcuFor(j, item);

                ProbeRecord row = toRecord(j, modelBytes, expectedWcu, billed);
                rows.add(row);
                System.out.printf(
                    "SIZE-MODEL-PROBE template=%d model_bytes=%d expected_wcu=%d billed_wcu=%.1f delta=%+.1f%n",
                    row.templateIndex(), row.modelBytes(), row.expectedWcu(), row.billedWcu(),
                    row.delta());

                // Aborts immediately on the first out-of-tolerance probe, exactly as before --
                // see checkBilled's own javadoc for why 0.5 WCU is not to be widened.
                checkBilled(j, modelBytes, expectedWcu, billed);
            }
        } finally {
            try {
                writeReport(resultsDir.resolve("size-model-probe.json"), rows);
            } catch (IOException e) {
                // A failure to write the audit file is a secondary problem: it must never mask
                // an abort (or a clean pass) already propagating out of the try above.
                System.err.println(
                    "SIZE-MODEL-VALIDATION: failed to write size-model-probe.json (continuing): "
                    + e.getMessage());
            }
        }
        System.out.println(
            "SIZE-MODEL-VALIDATION probes=" + rows.size() + " mismatches=0 result=PASS");
    }

    private static ProbeRecord toRecord(int templateIndex, int modelBytes, int expectedWcu,
                                        double billedWcu) {
        double delta = billedWcu - expectedWcu;
        return new ProbeRecord(templateIndex, modelBytes, expectedWcu, billedWcu, delta,
            withinTolerance(billedWcu, expectedWcu));
    }

    private static boolean withinTolerance(double billedWcu, int expectedWcu) {
        return Math.abs(billedWcu - expectedWcu) <= 0.5;
    }

    /**
     * Writes every probe row plus a summary (count, max absolute delta, overall pass/fail) as
     * machine-readable JSON, so a final report can cite real numbers instead of a single log
     * line. Extracted from {@link #runValidation} so it is directly unit-testable.
     */
    static void writeReport(Path out, List<ProbeRecord> rows) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("generatedAt", Instant.now().toString());
        ArrayNode arr = root.putArray("probes");
        double maxAbsDelta = 0.0;
        boolean allOk = true;
        for (ProbeRecord r : rows) {
            ObjectNode n = arr.addObject();
            n.put("templateIndex", r.templateIndex());
            n.put("modelBytes", r.modelBytes());
            n.put("expectedWcu", r.expectedWcu());
            n.put("billedWcu", r.billedWcu());
            n.put("delta", r.delta());
            n.put("ok", r.ok());
            maxAbsDelta = Math.max(maxAbsDelta, Math.abs(r.delta()));
            allOk &= r.ok();
        }
        ObjectNode summary = root.putObject("summary");
        summary.put("count", rows.size());
        summary.put("maxAbsDelta", maxAbsDelta);
        summary.put("pass", allOk);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
    }

    /**
     * Pure comparison, extracted out of {@link #validate} so the abort gate is unit-testable
     * without AWS. 0.5 WCU -- half of DynamoDB's finest billing granularity (1 KiB writes) --
     * is not to be widened: a wider tolerance would let a systematically wrong size model pass
     * silently, and this is the last check standing between a wrong model and 118 GiB of writes.
     */
    static void checkBilled(int templateIndex, int modelBytes, int expectedWcu, double billedWcu) {
        if (!withinTolerance(billedWcu, expectedWcu)) {
            throw new IllegalStateException(String.format(
                "size model mismatch at template %d: computed %d bytes -> %d WCU, "
                + "DynamoDB billed %.1f WCU. Aborting before loading 118 GiB.",
                templateIndex, modelBytes, expectedWcu, billedWcu));
        }
    }
}
