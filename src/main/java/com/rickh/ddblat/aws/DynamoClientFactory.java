package com.rickh.ddblat.aws;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.FullJitterBackoffStrategy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;

public final class DynamoClientFactory {

    private DynamoClientFactory() {}

    /** Production entry point: uses the default credentials provider chain. */
    public static DynamoDbClient create(Region region, ClientSettings s, URI endpointOverride) {
        return create(region, s, endpointOverride, null);
    }

    /**
     * @param credentials explicit provider, or null to use the default chain. Tests pass a
     *                    static local provider so an IT can never resolve real AWS credentials
     *                    and reach production AWS even by accident.
     */
    public static DynamoDbClient create(Region region, ClientSettings s, URI endpointOverride,
                                        AwsCredentialsProvider credentials) {
        ApacheHttpClient.Builder http = ApacheHttpClient.builder()
            .maxConnections(s.maxConnections())
            .connectionTimeToLive(s.connectionTtl())
            .connectionMaxIdleTime(s.connectionMaxIdle());

        RetryPolicy retry = RetryPolicy.builder()
            .numRetries(s.numRetries())
            .backoffStrategy(FullJitterBackoffStrategy.builder()
                .baseDelay(Duration.ofMillis(50))
                .maxBackoffTime(Duration.ofSeconds(1))
                .build())
            .build();

        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
            .apiCallTimeout(s.apiCallTimeout())
            .apiCallAttemptTimeout(s.apiCallAttemptTimeout())
            .retryPolicy(retry)
            .addExecutionInterceptor(new AttemptCounter())
            .build();

        var builder = DynamoDbClient.builder()
            .region(region)
            .httpClientBuilder(http)
            .overrideConfiguration(override);

        if (endpointOverride != null) builder.endpointOverride(endpointOverride);
        if (credentials != null) builder.credentialsProvider(credentials);
        return builder.build();
    }
}
