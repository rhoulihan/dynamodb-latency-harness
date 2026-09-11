package com.rickh.ddblat.report;

import com.rickh.ddblat.record.LatencyRecord;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Correlates above-P99.9 request samples against JVM pauses. A Java P99.9 measured while
 * allocating hundreds of MB/s is not credible unless it can state how much of the tail was
 * the collector rather than the service. This publishes that number instead of assuming it.
 */
public final class JfrAnalyzer {

    public record PauseReport(long pauseCount, long totalPauseNanos, long maxPauseNanos,
                              long tailSamples, long tailSamplesDuringPause,
                              double fractionOfTailDuringPause) {}

    private JfrAnalyzer() {}

    /** Half-open intervals: touching endpoints do not overlap. */
    public static boolean overlaps(long aStart, long aEnd, long bStart, long bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    public static PauseReport analyze(Path jfr, Path requestLog, byte phaseId,
                                      long p999Nanos, long runStartEpochNanos) throws IOException {
        List<long[]> pauses = new ArrayList<>();
        long total = 0, max = 0;
        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String name = e.getEventType().getName();
                if (!name.equals("jdk.GCPhasePause")
                        && !name.equals("jdk.ZAllocationStall")
                        && !name.equals("jdk.SafepointBegin")) {
                    continue;
                }
                long start = e.getStartTime().getEpochSecond() * 1_000_000_000L
                           + e.getStartTime().getNano() - runStartEpochNanos;
                long dur = e.getDuration().toNanos();
                pauses.add(new long[]{start, start + dur});
                total += dur;
                max = Math.max(max, dur);
            }
        }
        pauses.sort((x, y) -> Long.compare(x[0], y[0]));

        long tail = 0, tailDuringPause = 0;
        byte[] raw;
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(requestLog))) {
            raw = in.readAllBytes();
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int off = 0; off + LatencyRecord.BYTES <= raw.length; off += LatencyRecord.BYTES) {
            LatencyRecord.Decoded d = LatencyRecord.decode(buf, off);
            if (d.phaseId() != phaseId || d.latencyNanos() < p999Nanos) continue;
            tail++;
            long s = d.startNanos(), e = s + d.latencyNanos();
            for (long[] p : pauses) {
                if (p[0] >= e) break;
                if (overlaps(s, e, p[0], p[1])) { tailDuringPause++; break; }
            }
        }
        double fraction = tail == 0 ? 0.0 : (double) tailDuringPause / tail;
        return new PauseReport(pauses.size(), total, max, tail, tailDuringPause, fraction);
    }
}
