package com.rickh.ddblat.aws;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;

/**
 * Two jobs: confirm client-side capacity accounting agrees with DynamoDB's own, and capture
 * SuccessfulRequestLatency -- DynamoDB's server-side measurement. Differencing that against
 * the client-observed number yields network plus SDK overhead, which is a result in itself.
 */
public final class CloudWatchCrossCheck {

    public record Result(double cloudWatchUnits, double clientUnits, double deltaFraction,
                         double serverSideLatencyMicros, boolean withinTolerance) {}

    private CloudWatchCrossCheck() {}

    /**
     * Zero (or non-positive) CloudWatch units is treated as a failure, not a pass: the delta
     * is defined as 1.0 (100% disagreement) whenever both inputs are non-positive, and
     * withinTolerance additionally requires denom > 0 -- so a metric that never showed up
     * (wrong dimension, wrong metric name, wrong window) fails loudly instead of reading as
     * a divide-by-zero coincidence of perfect agreement.
     */
    public static Result evaluate(double cloudWatchUnits, double clientUnits,
                                  double serverSideLatencyMicros, double tolerance) {
        double denom = Math.max(cloudWatchUnits, clientUnits);
        double delta = denom <= 0 ? 1.0 : Math.abs(cloudWatchUnits - clientUnits) / denom;
        return new Result(cloudWatchUnits, clientUnits, delta, serverSideLatencyMicros,
                          denom > 0 && delta <= tolerance);
    }

    public static Result fetch(CloudWatchClient cw, String table, String operation,
                               Instant start, Instant end, boolean read,
                               double clientUnits, double tolerance) {
        String consumedMetric = read ? "ConsumedReadCapacityUnits" : "ConsumedWriteCapacityUnits";
        double units = sum(cw, table, consumedMetric, null, start, end, "Sum");
        double latencyMs = sum(cw, table, "SuccessfulRequestLatency", operation, start, end, "Average");
        return evaluate(units, clientUnits, latencyMs * 1000.0, tolerance);
    }

    private static double sum(CloudWatchClient cw, String table, String metric, String operation,
                              Instant start, Instant end, String stat) {
        var dims = new java.util.ArrayList<Dimension>();
        dims.add(Dimension.builder().name("TableName").value(table).build());
        if (operation != null) {
            dims.add(Dimension.builder().name("Operation").value(operation).build());
        }
        GetMetricStatisticsResponse resp = cw.getMetricStatistics(r -> r
            .namespace("AWS/DynamoDB")
            .metricName(metric)
            .dimensions(dims)
            .startTime(start).endTime(end)
            .period(60)
            .statistics(Statistic.fromValue(stat)));
        return resp.datapoints().stream()
            .mapToDouble(d -> "Sum".equals(stat) ? d.sum() : d.average())
            .reduce(0, "Sum".equals(stat) ? Double::sum : (a, b) -> (a + b) / 2);
    }
}
