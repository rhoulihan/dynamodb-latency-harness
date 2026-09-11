package com.rickh.ddblat.aws;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A single "put this local file at this key" operation, extracted so {@link ResultsUpload}'s
 * ordering guarantee (every artifact before DONE) can be unit-tested with a recording fake
 * instead of a real S3 bucket.
 */
public interface ResultsUploader {
    void put(String key, Path file) throws IOException;
}
