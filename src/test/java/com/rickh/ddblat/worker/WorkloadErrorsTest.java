package com.rickh.ddblat.worker;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throttle detection is what the run-validity gate depends on: a masked or misclassified
 * throttle would let a phase that DynamoDB rejected outright be reported as clean latency
 * data. Every status class WorkloadErrors.classify can produce is exercised here without a
 * container.
 */
class WorkloadErrorsTest {

    private static final long LATENCY_NANOS = 123_456L;
    private static final int ATTEMPTS = 3;

    @Test
    void provisionedThroughputExceededIsClassifiedAsThrottled() {
        var e = ProvisionedThroughputExceededException.builder()
            .message("Rate exceeded")
            .build();

        Workload.Outcome o = WorkloadErrors.classify(e, LATENCY_NANOS, ATTEMPTS);

        assertThat(o.statusClass()).isEqualTo(1);
        assertThat(o.throttled()).isTrue();
        assertThat(o.latencyNanos()).isEqualTo(LATENCY_NANOS);
        assertThat(o.attempts()).isEqualTo(ATTEMPTS);
    }

    @Test
    void requestLimitExceededIsClassifiedAsThrottled() {
        var e = RequestLimitExceededException.builder()
            .message("Throughput exceeds the current throughput limit")
            .build();

        Workload.Outcome o = WorkloadErrors.classify(e, LATENCY_NANOS, ATTEMPTS);

        assertThat(o.statusClass()).isEqualTo(1);
        assertThat(o.throttled()).isTrue();
        assertThat(o.latencyNanos()).isEqualTo(LATENCY_NANOS);
        assertThat(o.attempts()).isEqualTo(ATTEMPTS);
    }

    @Test
    void otherFourXxIsClassifiedAsStatusClassTwoNotThrottled() {
        AwsServiceException e = AwsServiceException.builder()
            .message("Missing required key")
            .statusCode(400)
            .build();

        Workload.Outcome o = WorkloadErrors.classify(e, LATENCY_NANOS, ATTEMPTS);

        assertThat(o.statusClass()).isEqualTo(2);
        assertThat(o.throttled()).isFalse();
        assertThat(o.latencyNanos()).isEqualTo(LATENCY_NANOS);
        assertThat(o.attempts()).isEqualTo(ATTEMPTS);
    }

    @Test
    void fiveXxIsClassifiedAsServerError() {
        AwsServiceException e = AwsServiceException.builder()
            .message("Internal server error")
            .statusCode(503)
            .build();

        Workload.Outcome o = WorkloadErrors.classify(e, LATENCY_NANOS, ATTEMPTS);

        assertThat(o.statusClass()).isEqualTo(3);
        assertThat(o.throttled()).isFalse();
        assertThat(o.latencyNanos()).isEqualTo(LATENCY_NANOS);
        assertThat(o.attempts()).isEqualTo(ATTEMPTS);
    }

    @Test
    void plainIoExceptionIsClassifiedAsTimeoutOrIo() {
        Exception e = new IOException("connection reset");

        Workload.Outcome o = WorkloadErrors.classify(e, LATENCY_NANOS, ATTEMPTS);

        assertThat(o.statusClass()).isEqualTo(4);
        assertThat(o.throttled()).isFalse();
        assertThat(o.latencyNanos()).isEqualTo(LATENCY_NANOS);
        assertThat(o.attempts()).isEqualTo(ATTEMPTS);
    }

    @Test
    void sdkClientExceptionIsClassifiedAsTimeoutOrIo() {
        Exception e = software.amazon.awssdk.core.exception.SdkClientException.create("Unable to execute HTTP request: timed out");

        Workload.Outcome o = WorkloadErrors.classify(e, LATENCY_NANOS, ATTEMPTS);

        assertThat(o.statusClass()).isEqualTo(4);
        assertThat(o.throttled()).isFalse();
        assertThat(o.latencyNanos()).isEqualTo(LATENCY_NANOS);
        assertThat(o.attempts()).isEqualTo(ATTEMPTS);
    }
}
