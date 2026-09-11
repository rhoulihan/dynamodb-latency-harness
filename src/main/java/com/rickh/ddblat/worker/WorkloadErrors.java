package com.rickh.ddblat.worker;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;

final class WorkloadErrors {

    private WorkloadErrors() {}

    static Workload.Outcome classify(Exception e, long latencyNanos, int attempts) {
        if (e instanceof ProvisionedThroughputExceededException
                || e instanceof RequestLimitExceededException) {
            return new Workload.Outcome(latencyNanos, 0, attempts, 1, true);
        }
        if (e instanceof AwsServiceException ase) {
            int status = ase.statusCode();
            if (status >= 500) return new Workload.Outcome(latencyNanos, 0, attempts, 3, false);
            return new Workload.Outcome(latencyNanos, 0, attempts, 2, false);
        }
        return new Workload.Outcome(latencyNanos, 0, attempts, 4, false);
    }
}
