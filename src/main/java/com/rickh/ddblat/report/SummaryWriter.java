package com.rickh.ddblat.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rickh.ddblat.metrics.PhaseStats;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class SummaryWriter {

    public record PhaseSummary(String name,
                               PhaseStats.Percentiles raw,
                               PhaseStats.Percentiles corrected,
                               long throttles,
                               long retries,
                               long hits,
                               long misses,
                               double achievedUnitsPerSec,
                               boolean valid,
                               List<String> violations) {}

    private static final ObjectMapper M = new ObjectMapper();

    private SummaryWriter() {}

    public static void write(Path out, List<PhaseSummary> phases) throws IOException {
        ObjectNode root = M.createObjectNode();
        root.put("generatedAt", java.time.Instant.now().toString());
        ArrayNode arr = root.putArray("phases");
        for (PhaseSummary p : phases) {
            ObjectNode n = arr.addObject();
            n.put("name", p.name());
            n.set("raw", percentiles(p.raw()));
            n.set("corrected", percentiles(p.corrected()));
            n.put("throttles", p.throttles());
            n.put("retries", p.retries());
            n.put("hits", p.hits());
            n.put("misses", p.misses());
            n.put("achievedUnitsPerSec", p.achievedUnitsPerSec());
            n.put("valid", p.valid());
            ArrayNode v = n.putArray("violations");
            p.violations().forEach(v::add);
        }
        M.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
    }

    /** All latency values are microseconds. */
    private static ObjectNode percentiles(PhaseStats.Percentiles p) {
        ObjectNode n = M.createObjectNode();
        n.put("p50", p.p50());
        n.put("p90", p.p90());
        n.put("p95", p.p95());
        n.put("p99", p.p99());
        n.put("p999", p.p999());
        n.put("p9999", p.p9999());
        n.put("max", p.max());
        n.put("mean", p.mean());
        n.put("stddev", p.stddev());
        n.put("count", p.count());
        return n;
    }
}
