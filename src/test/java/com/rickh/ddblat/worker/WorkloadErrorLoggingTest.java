package com.rickh.ddblat.worker;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.io.*;
import static org.assertj.core.api.Assertions.assertThat;

class WorkloadErrorLoggingTest {

    private PrintStream realErr;
    private ByteArrayOutputStream captured;

    @BeforeEach void setUp() {
        WorkloadErrors.reset();
        realErr = System.err;
        captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
    }

    @AfterEach void tearDown() { System.setErr(realErr); }

    private static DynamoDbException ex(String msg) {
        return (DynamoDbException) DynamoDbException.builder().message(msg).statusCode(400).build();
    }

    @Test
    void logsTheFirstOccurrenceSoASilentlyFailingRunIsDiagnosable() {
        WorkloadErrors.classify(ex("Invalid credential."), 1_000, 1);
        assertThat(captured.toString())
            .contains("WORKLOAD-ERROR (first occurrence)")
            .contains("Invalid credential.");
    }

    @Test
    void repeatsOfTheSameFailureAreCountedNotReprinted() {
        for (int i = 0; i < 500; i++) WorkloadErrors.classify(ex("Invalid credential."), 1_000, 1);
        assertThat(captured.toString().split("first occurrence", -1).length - 1)
            .as("500 identical failures must not produce 500 log lines")
            .isEqualTo(1);

        WorkloadErrors.reportSummary("LOAD");
        assertThat(captured.toString()).contains("WORKLOAD-ERROR-SUMMARY phase=LOAD count=500");
    }

    @Test
    void requestIdsDoNotMakeEveryFailureLookDistinct() {
        // Without normalisation, a per-request id in the message would defeat the whole cap.
        for (int i = 0; i < 50; i++) {
            WorkloadErrors.classify(ex("Throughput exceeded (Request ID: abc" + i + "def)"), 1, 1);
        }
        assertThat(WorkloadErrors.distinctCount()).isEqualTo(1);
    }

    @Test
    void classificationIsUnchangedByTheLogging() {
        assertThat(WorkloadErrors.classify(ex("bad"), 5, 2).statusClass()).isEqualTo(2);
        assertThat(WorkloadErrors.classify(new java.io.IOException("net"), 5, 2).statusClass()).isEqualTo(4);
    }
}
