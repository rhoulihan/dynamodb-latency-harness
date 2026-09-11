package com.rickh.ddblat.aws;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Duration;
import java.time.Instant;

public final class TableAdmin {

    private final DynamoDbClient ddb;
    private final String tableName;

    public TableAdmin(DynamoDbClient ddb, String tableName) {
        this.ddb = ddb;
        this.tableName = tableName;
    }

    public boolean exists() {
        try {
            ddb.describeTable(r -> r.tableName(tableName));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    public void createIfAbsent(long rcu, long wcu) {
        if (exists()) return;
        try {
            ddb.createTable(r -> r
                .tableName(tableName)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder()
                    .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder()
                    .readCapacityUnits(rcu).writeCapacityUnits(wcu).build()));
        } catch (ResourceInUseException e) {
            // created concurrently; fine
        }
    }

    /**
     * Sets provisioned capacity, treating "already there" as success.
     * <p>
     * DynamoDB rejects an UpdateTable that requests the capacity the table already has
     * ("The provisioned throughput for the table will not change."). Every transition a
     * single run makes is a real change, so this never mattered until runs were repeated
     * back to back: run N's TEARDOWN drops to 10/10 and run N+1's SWITCH raises again, and
     * a run that dies between those two points leaves the next one asking for the capacity
     * it is already sitting at -- which would abort an hours-long measurement sequence over
     * a no-op. Checking first is also the honest thing to log.
     */
    public void updateCapacity(long rcu, long wcu) {
        ProvisionedThroughputDescription current =
            describe().provisionedThroughput();
        if (current != null
            && current.readCapacityUnits() == rcu
            && current.writeCapacityUnits() == wcu) {
            System.out.println("CAPACITY: already at RCU " + rcu + " / WCU " + wcu
                + " -- no UpdateTable issued");
            return;
        }
        ddb.updateTable(r -> r
            .tableName(tableName)
            .provisionedThroughput(ProvisionedThroughput.builder()
                .readCapacityUnits(rcu).writeCapacityUnits(wcu).build()));
    }

    /** UpdateTable is asynchronous; poll until ACTIVE *and* the new values are reflected. */
    public void awaitActiveWithCapacity(long rcu, long wcu, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            TableDescription d = describe();
            ProvisionedThroughputDescription pt = d.provisionedThroughput();
            if (d.tableStatus() == TableStatus.ACTIVE
                    && pt.readCapacityUnits() == rcu
                    && pt.writeCapacityUnits() == wcu) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + tableName, e);
            }
        }
        throw new IllegalStateException(
            "table " + tableName + " did not reach ACTIVE with rcu=" + rcu + " wcu=" + wcu
            + " within " + timeout);
    }

    /**
     * Asserts the table is already at the given capacity, without changing it.
     * <p>
     * The counterpart to {@code manageCapacity=false}: when the caller owns capacity across a
     * series of runs, each run must still confirm what it is about to drive load against.
     * Driving 36,000 RCU/s at a table left at 10 RCU throttles from the first request and
     * produces a measurement window made entirely of retry latencies -- plausible numbers that
     * mean nothing, which is the failure mode this harness has been bitten by before.
     */
    public void requireCapacity(long rcu, long wcu) {
        ProvisionedThroughputDescription t = describe().provisionedThroughput();
        long actualRcu = t == null || t.readCapacityUnits() == null ? -1 : t.readCapacityUnits();
        long actualWcu = t == null || t.writeCapacityUnits() == null ? -1 : t.writeCapacityUnits();
        if (actualRcu != rcu || actualWcu != wcu) {
            throw new IllegalStateException(
                "table " + tableName + " is at RCU " + actualRcu + " / WCU " + actualWcu
                + " but this run requires RCU " + rcu + " / WCU " + wcu + ". manageCapacity=false "
                + "means the caller sets capacity before the run; nothing did.");
        }
        System.out.println("CAPACITY: verified RCU " + rcu + " / WCU " + wcu
            + " (manageCapacity=false -- not changing it)");
    }

    public TableDescription describe() {
        return ddb.describeTable(r -> r.tableName(tableName)).table();
    }

    public void deleteIfPresent() {
        try {
            ddb.deleteTable(r -> r.tableName(tableName));
        } catch (ResourceNotFoundException e) {
            // already gone
        }
    }
}
