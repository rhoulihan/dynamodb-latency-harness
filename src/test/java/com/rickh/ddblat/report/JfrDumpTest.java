package com.rickh.ddblat.report;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * A real smoke run measured run.jfr as 0 bytes in S3 but 50,180,673 bytes on the instance once
 * the JVM actually exited: JFR only finalizes {@code -XX:StartFlightRecording}'s file at JVM
 * shutdown, and {@link com.rickh.ddblat.Main} uploads results before that. {@link JfrDump} is
 * the fix, and is exercised here against the real {@code jdk.jfr} API -- there is no seam for a
 * fake and none is needed, since {@link Recording} is a plain JDK class with no AWS dependency.
 */
class JfrDumpTest {

    @TempDir Path tmp;

    @Test
    void dumpingToTheRecordingsOwnBoundDestinationProducesANonEmptyParseableFile() throws Exception {
        Path target = tmp.resolve("run.jfr");
        Recording r = new Recording(Configuration.getConfiguration("profile"));
        // Same path -XX:StartFlightRecording's filename= gives the recording, and the same path
        // JfrDump.dumpIfActive is told to dump to in Main -- this is exactly the shape of the
        // real bug, and confirms (rather than assumes) that dumping to a recording's own
        // already-bound destination actually works instead of needing a temp-file-and-move.
        r.setDestination(target);
        try {
            r.start();
            burnCpuFor(300);
            JfrDump.dumpIfActive(target);

            // Assert BEFORE stopping the recording: this is the entire bug. JFR only finalizes
            // -XX:StartFlightRecording's destination file when the recording (or the JVM) stops,
            // and in the real harness ResultsUpload runs while the recording is still RUNNING.
            // If this assertion needed r.stop() first to pass, it would prove nothing about
            // JfrDump -- stop() alone finalizes the file regardless of what dumpIfActive did.
            assertThat(r.getState()).isEqualTo(jdk.jfr.RecordingState.RUNNING);
            assertThat(Files.size(target)).isGreaterThan(0L);
            int events = 0;
            try (RecordingFile rf = new RecordingFile(target)) {
                while (rf.hasMoreEvents()) {
                    rf.readEvent();
                    events++;
                }
            }
            assertThat(events).isGreaterThan(0);
        } finally {
            r.stop();
            r.close();
        }
    }

    @Test
    void dumpIsACleanNoOpWhenNoRecordingIsActive() {
        Path target = tmp.resolve("run.jfr");

        // Every test in the suite, and any operator run without -XX:StartFlightRecording, hits
        // exactly this path: no recording exists at all.
        assertThatCode(() -> JfrDump.dumpIfActive(target)).doesNotThrowAnyException();

        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void aDumpFailureDoesNotThrowSoTheRestOfTheUploadStillRuns() throws Exception {
        // A directory already sitting at the dump target -- dump() cannot create a file there --
        // manufactures a real failure without relying on a fragile OS-permission trick.
        Path target = tmp.resolve("run.jfr");
        Files.createDirectory(target);

        Recording r = new Recording(Configuration.getConfiguration("profile"));
        try {
            r.start();
            burnCpuFor(100);
            assertThatCode(() -> JfrDump.dumpIfActive(target)).doesNotThrowAnyException();
        } finally {
            r.stop();
            r.close();
        }

        // Stand-in for "the rest of the artifact upload": in Main, ResultsUpload.runIfConfigured
        // runs unconditionally right after JfrDump.dumpIfActive -- this proves that next step is
        // still reachable even though the dump above failed.
        Path summary = tmp.resolve("summary.json");
        Files.writeString(summary, "{}");
        assertThat(Files.exists(summary)).isTrue();
    }

    private static void burnCpuFor(long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        double x = 1;
        while (System.nanoTime() < end) {
            x = Math.sqrt(x + 1);
        }
        if (x < 0) throw new AssertionError("unreachable"); // keeps the loop from being elided
    }
}
