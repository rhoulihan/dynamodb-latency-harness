package com.rickh.ddblat.aws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * {@link ResultsUpload} is the only guarantee that scripts/40-collect.sh's "DONE means every
 * artifact is present" assumption actually holds. These tests exercise the real ordering logic
 * against a recording fake, cheaply and without any real S3 bucket -- there is no seam in the
 * project for testing against real AWS, and none is needed here since the ordering itself is
 * pure orchestration over a {@link ResultsUploader}.
 */
class ResultsUploadTest {

    @TempDir Path tmp;

    /** Records every (key, file) pair in call order; never talks to AWS. */
    private static final class FakeUploader implements ResultsUploader {
        final List<String> keysInOrder = new ArrayList<>();

        @Override
        public void put(String key, Path file) {
            keysInOrder.add(key);
        }
    }

    private static final class FailingUploader implements ResultsUploader {
        @Override
        public void put(String key, Path file) throws IOException {
            throw new IOException("simulated upload failure for " + key);
        }
    }

    private void writeArtifact(String name) throws IOException {
        Files.writeString(tmp.resolve(name), "x");
    }

    @Test
    void runUploadsDoneLastAfterEveryOtherArtifact() throws Exception {
        writeArtifact("summary.json");
        writeArtifact("size-model-probe.json");
        writeArtifact("LOAD.hlog");
        writeArtifact("requests.bin.gz");
        writeArtifact("run.jfr");
        writeArtifact("ddblat.log");
        writeArtifact("DONE");   // written first on disk -- ordering must come from the code, not fs order

        FakeUploader fake = new FakeUploader();
        ResultsUpload.run(tmp, "results/run1", fake);

        assertThat(fake.keysInOrder).hasSize(7);
        assertThat(fake.keysInOrder.get(fake.keysInOrder.size() - 1)).isEqualTo("results/run1/DONE");
        assertThat(fake.keysInOrder.subList(0, 6)).doesNotContain("results/run1/DONE");
        assertThat(fake.keysInOrder).containsExactlyInAnyOrder(
            "results/run1/summary.json", "results/run1/size-model-probe.json",
            "results/run1/LOAD.hlog", "results/run1/requests.bin.gz",
            "results/run1/run.jfr", "results/run1/ddblat.log", "results/run1/DONE");
    }

    @Test
    void runDoesNotUploadDoneAtAllIfAnEarlierArtifactFailsToUpload() {
        writeArtifactUnchecked("summary.json");
        writeArtifactUnchecked("DONE");

        assertThatThrownBy(() -> ResultsUpload.run(tmp, "results/run1", new FailingUploader()))
            .isInstanceOf(IOException.class);
    }

    private void writeArtifactUnchecked(String name) {
        try {
            writeArtifact(name);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void runIfConfiguredSkipsUploadAndReturnsFalseWhenBucketIsBlank() throws Exception {
        writeArtifact("DONE");
        ResultsUploader neverCalled = (key, file) -> {
            throw new AssertionError("uploader must not be used when s3Bucket is blank: " + key);
        };

        boolean uploaded = ResultsUpload.runIfConfigured(tmp, "", "results/run1", () -> neverCalled);

        assertThat(uploaded).isFalse();
    }

    @Test
    void runIfConfiguredTreatsWhitespaceOnlyBucketAsBlankToo() throws Exception {
        writeArtifact("DONE");
        ResultsUploader neverCalled = (key, file) -> {
            throw new AssertionError("uploader must not be used when s3Bucket is blank: " + key);
        };

        boolean uploaded = ResultsUpload.runIfConfigured(tmp, "   ", "results/run1", () -> neverCalled);

        assertThat(uploaded).isFalse();
    }

    @Test
    void runIfConfiguredUploadsAndClosesTheUploaderWhenBucketIsSet() throws Exception {
        writeArtifact("DONE");
        class ClosingFakeUploader implements ResultsUploader, AutoCloseable {
            boolean closed = false;
            @Override public void put(String key, Path file) { }
            @Override public void close() { closed = true; }
        }
        ClosingFakeUploader fake = new ClosingFakeUploader();

        boolean uploaded = ResultsUpload.runIfConfigured(tmp, "my-bucket", "results/run1", () -> fake);

        assertThat(uploaded).isTrue();
        assertThat(fake.closed).isTrue();
    }
}
