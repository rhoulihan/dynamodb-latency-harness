package com.rickh.ddblat.aws;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Uploads every regular file directly inside a results directory to S3, DONE strictly last.
 *
 * scripts/40-collect.sh treats DONE's presence in the S3 prefix as "every artifact for this run
 * is present" -- it polls for exactly that key, then runs `aws s3 sync` on the whole prefix. If
 * DONE were uploaded first (or concurrently with, and finishing before, the rest), a collector
 * polling at just the wrong moment could sync a partial result set and believe the run is
 * complete. Uploading everything else first and DONE only once every other upload has returned
 * successfully is what makes DONE mean what 40-collect.sh assumes it means.
 */
public final class ResultsUpload {

    static final String DONE_FILE = "DONE";

    private ResultsUpload() {}

    /**
     * @param resultsDir local directory holding this run's artifacts (flat, no subdirectories)
     * @param s3Prefix   destination prefix, e.g. "results/smoke-20260908T...". No leading or
     *                   trailing slash.
     * @param uploader   does the actual put; a real one talks to S3, a fake records calls
     */
    public static void run(Path resultsDir, String s3Prefix, ResultsUploader uploader) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(resultsDir)) {
            files = listing.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        Path done = null;
        List<Path> rest = new ArrayList<>(files.size());
        for (Path f : files) {
            if (f.getFileName().toString().equals(DONE_FILE)) {
                done = f;
            } else {
                rest.add(f);
            }
        }

        for (Path f : rest) {
            uploader.put(key(s3Prefix, f), f);
        }
        // DONE last, and only reached if every upload above completed without throwing.
        if (done != null) {
            uploader.put(key(s3Prefix, done), done);
        }
    }

    private static String key(String s3Prefix, Path file) {
        String name = file.getFileName().toString();
        return s3Prefix.isBlank() ? name : s3Prefix + "/" + name;
    }

    /**
     * Entry point {@link com.rickh.ddblat.Main} actually calls. An empty/blank bucket means
     * local-only operation (every unit test, and any operator running the jar off-AWS) --
     * skip silently and return false rather than construct an S3 client at all. The uploader
     * is built lazily (only once the bucket check passes) via {@code uploaderSupplier} so the
     * blank-bucket path never touches AWS, and is closed afterward if it is {@link AutoCloseable}.
     */
    public static boolean runIfConfigured(Path resultsDir, String s3Bucket, String s3Prefix,
            Supplier<? extends ResultsUploader> uploaderSupplier) throws IOException {
        if (s3Bucket == null || s3Bucket.isBlank()) {
            return false;
        }
        ResultsUploader uploader = uploaderSupplier.get();
        try {
            run(resultsDir, s3Prefix, uploader);
        } finally {
            if (uploader instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception ignore) {
                    // closing the client is cleanup, not correctness -- never mask a real
                    // upload failure (or success) with a close-time exception.
                }
            }
        }
        return true;
    }
}
