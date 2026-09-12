package com.rickh.ddblat.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * DynamoDB rejects an UpdateTable that asks for the capacity the table already has:
 * "The provisioned throughput for the table will not change." A single run never hit this
 * because every transition it makes is a real change, but a repeated-measurement sequence
 * does -- run N's TEARDOWN drops to 10/10 and run N+1's SWITCH raises again, and any run
 * that dies between those two points leaves the next one asking for a capacity it is
 * already at. That is a crash in the middle of an hours-long sequence, so updateCapacity
 * treats "already there" as success.
 */
class TableAdminUpdateCapacityTest {

    /** Records UpdateTable calls; DescribeTable reports whatever capacity it was seeded with. */
    private static final class FakeDdb implements DynamoDbClient {
        long rcu, wcu;
        final List<String> updates = new ArrayList<>();
        FakeDdb(long rcu, long wcu) { this.rcu = rcu; this.wcu = wcu; }

        @Override public String serviceName() { return "dynamodb"; }
        @Override public void close() { }

        @Override public DescribeTableResponse describeTable(DescribeTableRequest r) {
            return DescribeTableResponse.builder().table(TableDescription.builder()
                .tableName(r.tableName())
                .tableStatus(TableStatus.ACTIVE)
                .provisionedThroughput(ProvisionedThroughputDescription.builder()
                    .readCapacityUnits(rcu).writeCapacityUnits(wcu).build())
                .build()).build();
        }

        @Override public UpdateTableResponse updateTable(UpdateTableRequest r) {
            ProvisionedThroughput t = r.provisionedThroughput();
            updates.add(t.readCapacityUnits() + "/" + t.writeCapacityUnits());
            rcu = t.readCapacityUnits();
            wcu = t.writeCapacityUnits();
            return UpdateTableResponse.builder().build();
        }
    }

    @Test
    void issuesAnUpdateWhenTheCapacityActuallyChanges() {
        FakeDdb ddb = new FakeDdb(10, 10);
        new TableAdmin(ddb, "t").updateCapacity(40_000, 10);
        assertThat(ddb.updates).containsExactly("40000/10");
    }

    @Test
    void skipsTheUpdateEntirelyWhenTheTableIsAlreadyAtThatCapacity() {
        FakeDdb ddb = new FakeDdb(40_000, 10);
        new TableAdmin(ddb, "t").updateCapacity(40_000, 10);
        assertThat(ddb.updates).isEmpty();
    }

    @Test
    void stillUpdatesWhenOnlyOneOfTheTwoDimensionsMatches() {
        FakeDdb ddb = new FakeDdb(40_000, 10);
        new TableAdmin(ddb, "t").updateCapacity(40_000, 30_000);
        assertThat(ddb.updates).containsExactly("40000/30000");
    }

    // ---- requireCapacity: verify without managing ---------------------------------------------

    @Test
    void requireCapacityPassesWhenTheTableIsAtTheExpectedCapacity() {
        FakeDdb ddb = new FakeDdb(40_000, 10);
        assertThatCode(() -> new TableAdmin(ddb, "t").requireCapacity(40_000, 10))
            .doesNotThrowAnyException();
        assertThat(ddb.updates).isEmpty();
    }

    @Test
    void requireCapacityFailsLoudlyWhenTheTableIsNotAtTheExpectedCapacity() {
        // The failure this prevents: driving 36,000 RCU/s at a table still sitting at 10 RCU,
        // which throttles from the first request and yields a window full of retry latencies.
        FakeDdb ddb = new FakeDdb(10, 10);
        assertThatThrownBy(() -> new TableAdmin(ddb, "t").requireCapacity(40_000, 10))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("40000")
            .hasMessageContaining("10");
        assertThat(ddb.updates).isEmpty();
    }

    // ---- ensureCapacity: the re-run path -------------------------------------------------

    @Test
    void ensureCapacityIssuesTheUpdateThatCreateIfAbsentWouldNotHave() {
        // A table left at 10/10 by a previous run's teardown. createIfAbsent would no-op and the
        // await would then block on a capacity nothing had set.
        FakeDdb ddb = new FakeDdb(10, 10);
        new TableAdmin(ddb, "t").ensureCapacity(10, 30_000, java.time.Duration.ofSeconds(5));
        assertThat(ddb.updates).containsExactly("10/30000");
        assertThat(ddb.rcu).isEqualTo(10);
        assertThat(ddb.wcu).isEqualTo(30_000);
    }

    @Test
    void ensureCapacityIsAnEfficientNoOpWhenAlreadyThere() {
        FakeDdb ddb = new FakeDdb(10, 30_000);
        new TableAdmin(ddb, "t").ensureCapacity(10, 30_000, java.time.Duration.ofSeconds(5));
        assertThat(ddb.updates).isEmpty();
    }
}
