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
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkloadIT {

    @Container
    static GenericContainer<?> local =
        new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
            .withExposedPorts(8000);

    static URI endpoint;
    static DynamoDbClient ddb;
    static KeySpace keys;
    static ItemTemplateFactory factory;
    static final String TABLE = "wl-table";

    private static AwsCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
    }

    @BeforeAll
    static void setUp() {
        endpoint = URI.create("http://" + local.getHost() + ":" + local.getFirstMappedPort());
        // Routed through the production factory so the retry policy, connection pool, timeouts,
        // and AttemptCounter registration are exercised by the same code path Main uses. Static
        // local credentials guarantee this can never resolve to a real AWS account.
        ddb = DynamoClientFactory.create(Region.US_EAST_1, ClientSettings.forThreads(4), endpoint,
            localCredentials());
        TableAdmin admin = new TableAdmin(ddb, TABLE);
        admin.createIfAbsent(100, 100);
        admin.awaitActiveWithCapacity(100, 100, Duration.ofSeconds(30));
        keys = new KeySpace(1024);
        factory = new ItemTemplateFactory(7L);
    }

    @Test
    void putReturnsASuccessOutcomeWithAMeasuredLatency() {
        PutWorkload put = new PutWorkload(ddb, TABLE, keys, factory, (byte) 0);
        Workload.Outcome o = put.execute(3);
        assertThat(o.statusClass()).isEqualTo(0);
        assertThat(o.throttled()).isFalse();
        assertThat(o.latencyNanos()).isPositive();
        assertThat(o.attempts()).isEqualTo(1);
        // Every throughput number and the capacity meter come from ConsumedCapacity in the
        // response; DynamoDB Local does populate it for PutItem when TOTAL is requested.
        assertThat(o.consumedCu()).isGreaterThan(0);
    }

    @Test
    void estimatedCapacityMatchesTheSizeModel() {
        PutWorkload put = new PutWorkload(ddb, TABLE, keys, factory, (byte) 0);
        int size = ItemSizeModel.sizeForKey(3);
        assertThat(put.estimatedCapacityUnits(3))
            .isEqualTo(ItemSizeModel.writeCapacityUnits(size));

        GetWorkload strong = new GetWorkload(ddb, TABLE, keys, true, (byte) 1);
        GetWorkload eventual = new GetWorkload(ddb, TABLE, keys, false, (byte) 2);
        assertThat(strong.estimatedCapacityUnits(3))
            .isEqualTo(ItemSizeModel.readCapacityUnitsStrong(size));
        assertThat(eventual.estimatedCapacityUnits(3))
            .isEqualTo(strong.estimatedCapacityUnits(3) / 2);
    }

    @Test
    void getRetrievesAFullTwentyAttributeItemThatWasWritten() {
        new PutWorkload(ddb, TABLE, keys, factory, (byte) 0).execute(11);
        GetWorkload get = new GetWorkload(ddb, TABLE, keys, true, (byte) 1);
        Workload.Outcome o = get.execute(11);
        assertThat(o.statusClass()).isEqualTo(0);
        assertThat(o.latencyNanos()).isPositive();
        assertThat(o.consumedCu()).isGreaterThan(0);
        assertThat(o.itemFound()).as("a real hit must be reported as found").isTrue();

        var item = ddb.getItem(r -> r.tableName(TABLE)
            .key(java.util.Map.of("pk",
                software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(keys.keyAt(11))))
            .consistentRead(true)).item();
        assertThat(item).hasSize(20);
        assertThat(ItemTemplateFactory.computeItemSizeBytes(item))
            .isEqualTo(ItemSizeModel.sizeForKey(11));
    }

    @Test
    void phaseIdIsCarriedThrough() {
        assertThat(new PutWorkload(ddb, TABLE, keys, factory, (byte) 0).phaseId()).isEqualTo((byte) 0);
        assertThat(new GetWorkload(ddb, TABLE, keys, true, (byte) 1).phaseId()).isEqualTo((byte) 1);
    }

    @Test
    void aMissingItemIsStillASuccessfulRequestNotAnError() {
        GetWorkload get = new GetWorkload(ddb, TABLE, keys, true, (byte) 1);
        Workload.Outcome o = get.execute(1023);   // never written
        assertThat(o.statusClass()).isEqualTo(0);
        assertThat(o.throttled()).isFalse();
        // task-17: a miss is a normal, successful response with no item in it -- DynamoDB
        // never reports it as an error, so this is the ONLY signal that distinguishes it
        // from a real hit. See GetWorkload.execute's use of GetItemResponse.hasItem().
        assertThat(o.itemFound()).as("a miss must be reported as not found, not silently "
            + "counted as a hit").isFalse();
    }

    /**
     * Do not trust DynamoDB Local's consistency semantics to prove this -- a strong and an
     * eventual read against Local can look identical on the wire back. Instead capture the
     * actual GetItemRequest/PutItemRequest that go out and assert the flags directly, which is
     * deterministic regardless of what Local does with them.
     */
    @Test
    void consistentReadAndReturnConsumedCapacityReachTheWireCorrectly() {
        List<GetItemRequest> capturedGets = new CopyOnWriteArrayList<>();
        List<PutItemRequest> capturedPuts = new CopyOnWriteArrayList<>();
        ExecutionInterceptor capture = new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attrs) {
                if (context.request() instanceof GetItemRequest g) capturedGets.add(g);
                if (context.request() instanceof PutItemRequest p) capturedPuts.add(p);
            }
        };
        DynamoDbClient capturingDdb = DynamoDbClient.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(endpoint)
            .credentialsProvider(localCredentials())
            .overrideConfiguration(o -> o.addExecutionInterceptor(capture))
            .build();

        new PutWorkload(capturingDdb, TABLE, keys, factory, (byte) 0).execute(777);
        new GetWorkload(capturingDdb, TABLE, keys, true, (byte) 1).execute(777);
        new GetWorkload(capturingDdb, TABLE, keys, false, (byte) 2).execute(777);

        assertThat(capturedPuts).hasSize(1);
        assertThat(capturedPuts.get(0).returnConsumedCapacity()).isEqualTo(ReturnConsumedCapacity.TOTAL);

        assertThat(capturedGets).hasSize(2);
        assertThat(capturedGets.get(0).consistentRead()).isTrue();
        assertThat(capturedGets.get(0).returnConsumedCapacity()).isEqualTo(ReturnConsumedCapacity.TOTAL);
        assertThat(capturedGets.get(1).consistentRead()).isNotEqualTo(Boolean.TRUE);
    }

    /**
     * Secondary, best-effort check: on real DynamoDB a strong read costs 2x an eventual read of
     * the same item. Kept only because DynamoDB Local was observed to report this ratio
     * faithfully for this item size; if that ever regresses, drop this test rather than the
     * wire-level one above, which is what actually defends the strong-vs-eventual invariant.
     */
    @Test
    void strongReadReportsRoughlyTwiceTheConsumedCapacityOfAnEventualRead() {
        new PutWorkload(ddb, TABLE, keys, factory, (byte) 0).execute(42);
        double strongCu = new GetWorkload(ddb, TABLE, keys, true, (byte) 1).execute(42).consumedCu();
        double eventualCu = new GetWorkload(ddb, TABLE, keys, false, (byte) 2).execute(42).consumedCu();
        assertThat(strongCu).isEqualTo(eventualCu * 2);
    }
}
