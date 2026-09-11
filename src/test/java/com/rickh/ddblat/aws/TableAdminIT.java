package com.rickh.ddblat.aws;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TableAdminIT {

    @Container
    static GenericContainer<?> local =
        new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
            .withExposedPorts(8000);

    static DynamoDbClient ddb;

    @BeforeAll
    static void setUp() {
        URI endpoint = URI.create("http://" + local.getHost() + ":" + local.getFirstMappedPort());
        // Routed through the production factory so the retry policy, connection pool, timeouts,
        // and AttemptCounter registration are exercised by the same code path Main uses. Static
        // local credentials guarantee this can never resolve to a real AWS account.
        ddb = DynamoClientFactory.create(Region.US_EAST_1, ClientSettings.forThreads(4), endpoint,
            StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
    }

    @Test
    void createIsIdempotentAndSetsTheRequestedCapacity() {
        TableAdmin admin = new TableAdmin(ddb, "t-create");
        admin.createIfAbsent(10, 40_000);
        admin.createIfAbsent(10, 40_000);   // must not throw
        admin.awaitActiveWithCapacity(10, 40_000, Duration.ofSeconds(30));

        var pt = admin.describe().provisionedThroughput();
        assertThat(pt.readCapacityUnits()).isEqualTo(10L);
        assertThat(pt.writeCapacityUnits()).isEqualTo(40_000L);
    }

    @Test
    void updateCapacityFlipsReadAndWriteForThePhaseSwitch() {
        TableAdmin admin = new TableAdmin(ddb, "t-switch");
        admin.createIfAbsent(10, 40_000);
        admin.awaitActiveWithCapacity(10, 40_000, Duration.ofSeconds(30));

        admin.updateCapacity(40_000, 10);
        admin.awaitActiveWithCapacity(40_000, 10, Duration.ofSeconds(30));

        var pt = admin.describe().provisionedThroughput();
        assertThat(pt.readCapacityUnits()).isEqualTo(40_000L);
        assertThat(pt.writeCapacityUnits()).isEqualTo(10L);
    }

    @Test
    void existsAndDeleteBehaveAsExpected() {
        TableAdmin admin = new TableAdmin(ddb, "t-life");
        assertThat(admin.exists()).isFalse();
        admin.createIfAbsent(10, 10);
        admin.awaitActiveWithCapacity(10, 10, Duration.ofSeconds(30));
        assertThat(admin.exists()).isTrue();
        admin.deleteIfPresent();
        admin.deleteIfPresent();            // idempotent
        assertThat(admin.exists()).isFalse();
    }

    @Test
    void awaitTimesOutRatherThanHangingForever() {
        TableAdmin admin = new TableAdmin(ddb, "t-timeout");
        admin.createIfAbsent(10, 10);
        assertThatThrownBy(() ->
            admin.awaitActiveWithCapacity(99_999, 99_999, Duration.ofSeconds(2)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("did not reach");
    }

    @Test
    void tableHasPartitionKeyOnlyAndNoSecondaryIndexes() {
        TableAdmin admin = new TableAdmin(ddb, "t-schema");
        admin.createIfAbsent(10, 10);
        admin.awaitActiveWithCapacity(10, 10, Duration.ofSeconds(30));
        var d = admin.describe();
        assertThat(d.keySchema()).hasSize(1);
        assertThat(d.keySchema().get(0).attributeName()).isEqualTo("pk");
        assertThat(d.globalSecondaryIndexes()).isNullOrEmpty();
        assertThat(d.localSecondaryIndexes()).isNullOrEmpty();
    }
}
