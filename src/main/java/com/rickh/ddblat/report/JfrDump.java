package com.rickh.ddblat.report;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Forces the active JFR recording (if any) to flush to {@code target} before the process exits.
 *
 * JFR only finalizes {@code -XX:StartFlightRecording=...,filename=X} at JVM shutdown -- X stays
 * empty for the entire run and is populated only during the exit sequence. {@link
 * com.rickh.ddblat.Main} uploads results (including that same file) before the JVM exits, so
 * without this the uploaded run.jfr is always 0 bytes -- exactly what a real smoke run measured
 * (0 bytes in S3, 50,180,673 bytes on the instance once the JVM had actually exited). {@link
 * Recording#dump} does not have that problem: it flushes whatever has been captured so far to a
 * file immediately, independent of process exit. Dumping to the exact path the recording is
 * already bound to (the same path {@code -XX:StartFlightRecording}'s {@code filename=} names)
 * was verified to work -- not assumed -- against a real {@code Configuration.getConfiguration
 * ("profile")} recording before this was wired into {@code Main}; no temp-file-and-move
 * indirection is needed.
 *
 * Every test in this project, and any operator running the jar without
 * {@code -XX:StartFlightRecording}, has no active recording at all -- that is the normal case,
 * not an error, so {@link #dumpIfActive} is then a silent no-op. Losing the recording is bad;
 * breaking the run over it, or blocking the rest of the artifact upload, would be worse -- every
 * failure here is caught, logged once naming the target path, and swallowed.
 */
public final class JfrDump {

    private JfrDump() {}

    /**
     * Dumps every recording JFR currently knows about to {@code target}, overwriting it. No-op,
     * without logging anything, if JFR is not enabled on this JVM or no recording is running --
     * both are the default for every test and any run without {@code -XX:StartFlightRecording}.
     * Never throws: a dump failure is logged to stderr, naming {@code target}, and swallowed so
     * the caller's remaining artifact upload still runs.
     */
    public static void dumpIfActive(Path target) {
        List<Recording> recordings;
        try {
            recordings = FlightRecorder.getFlightRecorder().getRecordings();
        } catch (Throwable t) {
            // JFR unsupported/disabled on this JVM entirely. Not an error -- just nothing to do.
            return;
        }
        if (recordings.isEmpty()) {
            return;
        }

        boolean anyDumped = false;
        for (Recording r : recordings) {
            try {
                r.dump(target);
                anyDumped = true;
            } catch (Exception e) {
                System.err.println("WARNING: failed to dump JFR recording '" + r.getName()
                    + "' to " + target + " -- the run's own results are unaffected, but the "
                    + "uploaded flight recording may be missing or incomplete: " + e);
            }
        }
        if (!anyDumped) {
            return;
        }

        // Belt-and-suspenders: dump() above either wrote real data or threw (already caught,
        // logged, and skipped). This only fires if it somehow returned normally having written
        // nothing -- worth a loud, plain warning rather than silently letting a 0-byte run.jfr
        // upload and look like a complete capture.
        try {
            long size = Files.exists(target) ? Files.size(target) : -1;
            if (size <= 0) {
                System.err.println("WARNING: JFR dump to " + target + " produced no data ("
                    + (size < 0 ? "file does not exist" : "0 bytes")
                    + ") -- the uploaded recording, if any, will not reflect this run.");
            }
        } catch (IOException e) {
            System.err.println("WARNING: could not verify the JFR dump at " + target
                + " after writing it: " + e);
        }
    }
}
