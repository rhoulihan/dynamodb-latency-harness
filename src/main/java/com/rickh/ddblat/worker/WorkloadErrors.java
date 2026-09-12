package com.rickh.ddblat.worker;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class WorkloadErrors {

    /**
     * Every distinct failure is logged ONCE, with a running count published at the end of the
     * phase.
     *
     * Without this, a run in which every single request fails looks identical in the tick log
     * to one that is merely slow: {@code completed} climbs, {@code hits} stays at zero, and
     * achieved capacity sits at 0.00 with no indication of why. That is exactly what happened
     * on the first OCI run, and diagnosing it meant reproducing the call path by hand outside
     * the harness. A measurement tool that cannot say why it failed forces its operator to
     * rebuild it from scratch to find out.
     *
     * Bounded on purpose: one line per distinct (class, message-shape) pair, so a phase that
     * fails 2 million times prints a handful of lines rather than flooding the log it is
     * supposed to make readable.
     */
    private static final Map<String, AtomicLong> SEEN = new ConcurrentHashMap<>();
    private static final int MAX_DISTINCT_LOGGED = 12;

    private WorkloadErrors() {}

    static Workload.Outcome classify(Exception e, long latencyNanos, int attempts) {
        note(e);
        if (e instanceof ProvisionedThroughputExceededException
                || e instanceof RequestLimitExceededException) {
            return new Workload.Outcome(latencyNanos, 0, attempts, 1, true);
        }
        if (e instanceof AwsServiceException ase) {
            int status = ase.statusCode();
            if (status >= 500) return new Workload.Outcome(latencyNanos, 0, attempts, 3, false);
            return new Workload.Outcome(latencyNanos, 0, attempts, 2, false);
        }
        return new Workload.Outcome(latencyNanos, 0, attempts, 4, false);
    }

    /** Key on type + a trimmed message so request ids and keys don't make every error distinct. */
    private static void note(Exception e) {
        String key = e.getClass().getSimpleName() + ": " + shape(e.getMessage());
        AtomicLong n = SEEN.computeIfAbsent(key, k -> {
            if (SEEN.size() < MAX_DISTINCT_LOGGED) {
                System.err.println("WORKLOAD-ERROR (first occurrence) " + k);
            }
            return new AtomicLong();
        });
        n.incrementAndGet();
    }

    private static String shape(String message) {
        if (message == null) return "(no message)";
        String m = message.replaceAll("\\s+", " ")
                          .replaceAll("Request ID: \\S+", "Request ID: ...")
                          .replaceAll("\\b[0-9a-fA-F]{16,}\\b", "...");
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    /** Printed at phase end so a run's artifacts record what went wrong and how often. */
    static void reportSummary(String phase) {
        if (SEEN.isEmpty()) return;
        SEEN.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
            .forEach(en -> System.err.println(
                "WORKLOAD-ERROR-SUMMARY phase=" + phase + " count=" + en.getValue().get()
                + " " + en.getKey()));
    }

    static void reset() { SEEN.clear(); }

    static long distinctCount() { return SEEN.size(); }
}
