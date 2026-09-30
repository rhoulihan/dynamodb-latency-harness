package com.rickh.ddblat.worker;

import com.rickh.ddblat.aws.ClientSettings;
import com.rickh.ddblat.aws.DynamoClientFactory;
import com.rickh.ddblat.aws.TableAdmin;
import com.rickh.ddblat.model.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The three batch workloads against a real DynamoDB API (DynamoDB Local) at MELI item sizes. */
@Testcontainers
class BatchWorkloadsIT {

    @Container
    static GenericContainer<?> local =
        new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest")).withExposedPorts(8000);

    static DynamoDbClient ddb;
    static KeySpace keys;
    static final String TABLE = "batch-table";

    @BeforeAll
    static void setUp() {
        URI endpoint = URI.create("http://" + local.getHost() + ":" + local.getFirstMappedPort());
        ddb = DynamoClientFactory.create(Region.US_EAST_1, ClientSettings.forThreads(4), endpoint,
            StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        TableAdmin admin = new TableAdmin(ddb, TABLE);
        admin.createIfAbsent(1000, 1000);
        admin.awaitActiveWithCapacity(1000, 1000, Duration.ofSeconds(30));
        keys = new KeySpace(1024);
    }

    @Test
    void batchPutThenBatchGetRoundTripsEveryItemAtSize() {
        var f = new ItemTemplateFactory(11L, 2_000);
        var put = new BatchPutWorkload(ddb, TABLE, keys, 25, f, (byte) 11);
        for (int i = 0; i < 4; i++) assertThat(put.execute(i).statusClass()).isZero();   // keys 0..99

        var get = new BatchGetWorkload(ddb, TABLE, keys, 100, true, (byte) 7, 2_000);
        Workload.Outcome o = get.execute(0);
        assertThat(o.statusClass()).isZero();
        assertThat(o.itemFound()).as("all 100 keys written by the batch puts must come back").isTrue();

        Map<String, AttributeValue> item = ddb.getItem(r -> r.tableName(TABLE)
            .key(Map.of("pk", AttributeValue.fromS(keys.keyAt(42))))).item();
        assertThat(ItemTemplateFactory.computeItemSizeBytes(item)).isEqualTo(2_000);
    }

    @Test
    void transactionOfOneHundredCommitsAtomically() {
        var f = new ItemTemplateFactory(12L, 523);
        var tw = new TransactWriteWorkload(ddb, TABLE, keys, 100, f, (byte) 13);
        Workload.Outcome o = tw.execute(0);
        assertThat(o.statusClass()).as("transaction must commit").isZero();

        var get = new BatchGetWorkload(ddb, TABLE, keys, 100, false, (byte) 9, 523);
        assertThat(get.execute(0).itemFound()).isTrue();
    }

    @Test
    void fiftyKilobyteTransactionFitsUnderTheFourMegabyteCap() {
        var tw = new TransactWriteWorkload(ddb, TABLE, keys, 100, new ItemTemplateFactory(13L, 50_000), (byte) 13);
        assertThat(tw.itemsPerTransaction()).isEqualTo(83);
        assertThat(tw.execute(0).statusClass()).as("83 x 50 KB must not be rejected").isZero();
    }
}
