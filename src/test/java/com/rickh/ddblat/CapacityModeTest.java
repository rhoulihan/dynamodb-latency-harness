package com.rickh.ddblat;

import com.rickh.ddblat.provider.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a run sets capacity, verifies it, or leaves it entirely alone is now a three-way
 * decision over two independent inputs, and it is asked at two call sites (SWITCH and TEARDOWN).
 *
 * This codebase has been bitten before by a rule copied to several call sites -- four copies of
 * "facts live in exactly two places" is what blinded an earlier preservation gate. So the rule
 * is stated once here and both sites ask it.
 */
class CapacityModeTest {

    @TempDir Path tmp;

    private Config cfg(String body) throws Exception {
        Path p = tmp.resolve("c.properties");
        Files.writeString(p, body);
        return Config.load(p);
    }

    private static final String BASE = """
        table=t
        region=us-east-1
        itemCount=1024
        loadWcu=100
        readRcu=100
        resultsDir=/tmp
        """;

    @Test
    void awsDefaultSetsCapacity() throws Exception {
        assertThat(cfg(BASE).capacityMode()).isEqualTo(Config.CapacityMode.SET);
    }

    @Test
    void awsWithManageCapacityFalseVerifiesInstead() throws Exception {
        // A series runner owns capacity across runs; each run must still confirm what it is
        // about to drive load against.
        assertThat(cfg(BASE + "skipLoad=true\nmanageCapacity=false\n").capacityMode())
            .isEqualTo(Config.CapacityMode.VERIFY);
    }

    @Test
    void scyllaSkipsEntirelyEvenWithManageCapacityTrue() throws Exception {
        // Alternator ignores BillingMode and ProvisionedThroughput. Setting capacity is a no-op
        // and VERIFYING it is worse than a no-op: DescribeTable echoes back whatever was asked
        // for at create time, so the check would pass while testing nothing at all.
        var c = cfg(BASE + "provider=scylla\nscyllaEndpoint=https://node:8043\n"
                         + "scyllaKeyFile=/tmp/k.json\n");
        assertThat(c.provider()).isEqualTo(Provider.SCYLLA);
        assertThat(c.manageCapacity()).isTrue();
        assertThat(c.capacityMode()).isEqualTo(Config.CapacityMode.SKIP);
    }

    @Test
    void ociStillManagesCapacityNormally() throws Exception {
        var c = cfg(BASE + "provider=oci\nociDatabaseOcid=ocid1.autonomousdatabase.oc1.iad.x\n"
                         + "ociKeyFile=/tmp/k.json\n");
        assertThat(c.capacityMode()).isEqualTo(Config.CapacityMode.SET);
    }

    @Test
    void aSchemelessScyllaEndpointIsRejectedAtConfigLoad() {
        // ScyllaDB Cloud shows node addresses without a scheme, so this is the likely paste.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            cfg(BASE + "provider=scylla\nscyllaEndpoint=node-0.scylladb.com:8043\n"
                     + "scyllaKeyFile=/tmp/k.json\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http");
    }
}
