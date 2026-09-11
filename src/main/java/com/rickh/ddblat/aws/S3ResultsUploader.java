package com.rickh.ddblat.aws;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.file.Path;

/**
 * One PutObject call per file. Results artifacts are a handful of files (summary.json,
 * size-model-probe.json, three .hlog files, requests.bin.gz, DONE) plus run.jfr, which can be
 * up to 2 GiB -- well within PutObject's 5 GiB single-request limit, so a straightforward
 * single-shot upload is used for every file, run.jfr included. A multipart upload would be more
 * resilient to a mid-transfer network blip on that one large file, but is not implemented here:
 * this run happens exactly once at the very end of a successful 2.5-hour harness run, a retry
 * is cheap (the file is still on local EBS), and multipart adds real complexity for a single
 * call site. If run.jfr uploads prove flaky in practice, multipart is the next step -- not
 * silently added here.
 */
public final class S3ResultsUploader implements ResultsUploader, AutoCloseable {

    private final S3Client s3;
    private final String bucket;

    public S3ResultsUploader(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public static S3ResultsUploader create(Region region, String bucket) {
        return new S3ResultsUploader(S3Client.builder().region(region).build(), bucket);
    }

    @Override
    public void put(String key, Path file) throws IOException {
        try {
            s3.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromFile(file));
        } catch (SdkException e) {
            throw new IOException(
                "failed to upload " + file + " to s3://" + bucket + "/" + key, e);
        }
    }

    @Override
    public void close() {
        s3.close();
    }
}
