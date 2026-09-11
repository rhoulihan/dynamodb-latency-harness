package com.rickh.ddblat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deletes every regular file directly inside {@code resultsDir} before a run begins.
 *
 * Live evidence: during run #3, {@code /opt/ddblat/results/} still contained run #2's {@code
 * DONE}, {@code summary.json}, and {@code .hlog} files, dated ~2 hours earlier -- the instance
 * had simply been reused without wiping its previous run's output. {@link
 * com.rickh.ddblat.aws.ResultsUpload} would have shipped those stale artifacts alongside this
 * run's, and a leftover {@code DONE} could convince a collector polling S3 that a failed run had
 * succeeded, before this run ever reached its own successful {@code DONE} write -- the single
 * most misleading failure available to this harness. {@link Main} calls {@link #clean} once, at
 * startup, before anything is written for the new run.
 */
final class ResultsDirCleaner {

    private ResultsDirCleaner() {}

    /**
     * Deletes every regular file directly inside {@code resultsDir} and logs what was removed.
     * Does not recurse into subdirectories -- {@link com.rickh.ddblat.aws.ResultsUpload}
     * documents {@code resultsDir} as flat, so none are expected, and leaving anything
     * unexpected alone rather than recursively deleting it is the safer failure mode. A silent
     * no-op if {@code resultsDir} does not exist yet (the very first run on a fresh instance);
     * {@link Main} creates the directory immediately after calling this.
     */
    static void clean(Path resultsDir) throws IOException {
        if (!Files.isDirectory(resultsDir)) {
            return;
        }
        List<Path> removed = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(resultsDir)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    Files.delete(entry);
                    removed.add(entry);
                }
            }
        }
        if (!removed.isEmpty()) {
            System.out.println("STARTUP: removed " + removed.size()
                + " stale artifact(s) from a prior run in " + resultsDir + ": " + removed);
        }
    }
}
