package com.rickh.ddblat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Live evidence: run #3 found run #2's DONE, summary.json, and .hlog files still sitting in
 * resultsDir, ~2 hours stale. A leftover DONE is the single most misleading artifact this
 * harness can ship -- it is exactly what a collector polls for to decide a run succeeded.
 */
class ResultsDirCleanerTest {

    @TempDir Path tmp;

    @Test
    void cleanRemovesEveryRegularFileDirectlyInsideResultsDir() throws IOException {
        Path resultsDir = tmp.resolve("results");
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("DONE"), "ok\n");
        Files.writeString(resultsDir.resolve("summary.json"), "{}");
        Files.writeString(resultsDir.resolve("LOAD.hlog"), "stale hlog data");
        Files.writeString(resultsDir.resolve("requests.bin.gz"), "stale");

        ResultsDirCleaner.clean(resultsDir);

        try (var listing = Files.list(resultsDir)) {
            assertThat(listing).isEmpty();
        }
    }

    @Test
    void cleanIsASilentNoOpWhenResultsDirDoesNotExistYet() {
        Path resultsDir = tmp.resolve("never-created");

        assertThatCode(() -> ResultsDirCleaner.clean(resultsDir)).doesNotThrowAnyException();

        assertThat(Files.exists(resultsDir)).isFalse();
    }

    @Test
    void cleanDoesNotTouchAnythingOutsideResultsDir() throws IOException {
        Path resultsDir = tmp.resolve("results");
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("DONE"), "ok\n");

        // A sibling of resultsDir, and the checkpoint file Config deliberately defaults outside
        // resultsDir for exactly this reason -- wiping results must never destroy resume state.
        Path sibling = tmp.resolve("load.checkpoint");
        Files.writeString(sibling, "12345");

        ResultsDirCleaner.clean(resultsDir);

        assertThat(Files.exists(sibling)).isTrue();
        assertThat(Files.readString(sibling)).isEqualTo("12345");
    }

    @Test
    void cleanLeavesSubdirectoriesAloneRatherThanRecursing() throws IOException {
        // resultsDir is documented (see ResultsUpload) as flat with no subdirectories; if one
        // somehow exists, leaving it alone is the safer failure mode than deleting recursively.
        Path resultsDir = tmp.resolve("results");
        Path nested = resultsDir.resolve("unexpected-subdir");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("inner.txt"), "left alone");
        Files.writeString(resultsDir.resolve("DONE"), "ok\n");

        ResultsDirCleaner.clean(resultsDir);

        assertThat(Files.exists(resultsDir.resolve("DONE"))).isFalse();
        assertThat(Files.exists(nested.resolve("inner.txt"))).isTrue();
    }
}
