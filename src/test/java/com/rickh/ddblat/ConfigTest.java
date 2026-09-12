package com.rickh.ddblat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ConfigTest {

    @TempDir Path tmp;

    private Path write(String body) throws Exception {
        Path p = tmp.resolve("c.properties");
        Files.writeString(p, body);
        return p;
    }

    @Test
    void loadsTheProductionShapedConfiguration() throws Exception {
        Config c = Config.load(write("""
            table=latency-test-100g
            region=us-east-1
            itemCount=2097152
            loadWcu=40000
            readRcu=40000
            targetFraction=0.90
            windowMinutes=20
            warmSeconds=60
            minRampMinutes=5
            initialThreads=32
            maxThreads=256
            resultsDir=/mnt/results
            s3Bucket=my-bucket
            selfStop=true
            """));
        assertThat(c.table()).isEqualTo("latency-test-100g");
        assertThat(c.itemCount()).isEqualTo(2_097_152);
        assertThat(c.loadWcu()).isEqualTo(40_000L);
        assertThat(c.readRcu()).isEqualTo(40_000L);
        assertThat(c.targetFraction()).isEqualTo(0.90);
        assertThat(c.windowDuration().toMinutes()).isEqualTo(20);
        assertThat(c.minRampNanos()).isEqualTo(5L * 60 * 1_000_000_000L);
        assertThat(c.selfStop()).isTrue();
    }

    @Test
    void rejectsANonPowerOfTwoItemCountBecauseTheReadCycleRequiresIt() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1000
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("power of two");
    }

    @Test
    void rejectsATargetFractionOutsideZeroToOne() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            targetFraction=1.5
            resultsDir=/tmp
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetFraction");
    }

    @Test
    void appliesDefaultsForOptionalKeys() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            """));
        assertThat(c.targetFraction()).isEqualTo(0.90);
        assertThat(c.windowDuration().toMinutes()).isEqualTo(20);
        assertThat(c.initialThreads()).isEqualTo(32);
        assertThat(c.maxThreads()).isEqualTo(256);
        assertThat(c.selfStop()).isFalse();
        assertThat(c.harnessLogFile()).isEqualTo(Path.of("/var/log/ddblat.log"));
    }

    @Test
    void harnessLogFileCanBeOverridden() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            harnessLogFile=/custom/path/harness.log
            """));
        assertThat(c.harnessLogFile()).isEqualTo(Path.of("/custom/path/harness.log"));
    }

    @Test
    void checkpointDefaultsOutsideResultsDirSoWipingResultsKeepsResumeState() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/mnt/run/results
            """));
        assertThat(c.checkpointFile().startsWith(c.resultsDir())).isFalse();
        assertThat(c.s3Prefix()).isEqualTo("results");
    }

    @Test
    void checkpointFileCanBeOverridden() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/mnt/run/results
            checkpointFile=/var/lib/ddblat/ck
            s3Prefix=results/run-7
            """));
        assertThat(c.checkpointFile()).isEqualTo(java.nio.file.Path.of("/var/lib/ddblat/ck"));
        assertThat(c.s3Prefix()).isEqualTo("results/run-7");
    }

    @Test
    void presplitWcuDefaultsToLoadWcuWhenAbsent() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=30000
            readRcu=100
            resultsDir=/tmp
            """));
        assertThat(c.presplitWcu()).isEqualTo(30_000L);
    }

    @Test
    void presplitWcuIsParsedWhenPresent() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=30000
            presplitWcu=40000
            readRcu=100
            resultsDir=/tmp
            """));
        assertThat(c.presplitWcu()).isEqualTo(40_000L);
        assertThat(c.loadWcu()).isEqualTo(30_000L);
    }

    @Test
    void rejectsAPresplitWcuBelowLoadWcuBecauseThatWouldBeWorseThanNotPresplitting() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=30000
            presplitWcu=20000
            readRcu=100
            resultsDir=/tmp
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("presplitWcu");
    }

    @Test
    void aMissingRequiredKeyNamesTheKey() throws Exception {
        assertThatThrownBy(() -> Config.load(write("region=us-east-1\n")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("table");
    }

    // ---- readSegmentsTotal / readSegmentsUsed: segment-scoped read key selection -------------

    @Test
    void segmentScopedReadsAreDisabledByDefault() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            """));
        assertThat(c.readSegmentsTotal()).isEqualTo(0);
        assertThat(c.readSegmentsUsed()).isEqualTo(0);
    }

    @Test
    void aValidSegmentsPairIsAccepted() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            readSegmentsTotal=40
            readSegmentsUsed=15
            """));
        assertThat(c.readSegmentsTotal()).isEqualTo(40);
        assertThat(c.readSegmentsUsed()).isEqualTo(15);
    }

    @Test
    void rejectsReadSegmentsUsedGreaterThanOrEqualToTotal() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            readSegmentsTotal=40
            readSegmentsUsed=40
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("readSegmentsUsed");

        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            readSegmentsTotal=40
            readSegmentsUsed=41
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("readSegmentsUsed");
    }

    @Test
    void rejectsReadSegmentsUsedSetWithoutReadSegmentsTotal() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            readSegmentsUsed=15
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("readSegmentsUsed")
            .hasMessageContaining("readSegmentsTotal");
    }

    @Test
    void rejectsReadSegmentsTotalSetWithoutReadSegmentsUsed() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            readSegmentsTotal=40
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("readSegmentsUsed");
    }

    @Test
    void rejectsANegativeReadSegmentsTotal() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            readSegmentsTotal=-1
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("readSegmentsTotal");
    }

    @Test
    void rejectsAZeroReadSegmentsUsedWhenTotalIsSet() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            readSegmentsTotal=40
            readSegmentsUsed=0
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("readSegmentsUsed");
    }

    // ---- skipLoad ---------------------------------------------------------------------------
    // A read-only re-run against a table some EARLIER run already loaded. Without this the
    // harness would re-create/pre-split the table, re-probe the size model with 100 PutItems,
    // and rewrite the whole dataset -- 118 GiB and ~76 minutes of write capacity -- purely to
    // get back to a state the table is already in.

    @Test
    void defaultsSkipLoadToFalseSoEveryExistingConfigStillLoads() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            """));
        assertThat(c.skipLoad()).isFalse();
    }

    @Test
    void readsSkipLoadWhenSet() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            skipLoad=true
            """));
        assertThat(c.skipLoad()).isTrue();
    }

    @Test
    void rejectsSkipLoadCombinedWithPresplitBecausePresplitOnlyAppliesAtCreation() throws Exception {
        // presplitWcu buys partitions at CREATE time. A skipLoad run never creates the table,
        // so a config asking for both is asking for something that cannot happen, and would
        // silently produce a run whose per-partition maths the operator has wrong.
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            presplitWcu=40000
            readRcu=100
            resultsDir=/tmp
            skipLoad=true
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("skipLoad");
    }

    // ---- manageCapacity ---------------------------------------------------------------------
    // DynamoDB allows 4 provisioned-throughput DECREASES per table per UTC day, then one per
    // hour. A run that owns its own capacity spends one decrease at TEARDOWN, so a five-run
    // series spends five and stalls an hour between the later ones. manageCapacity=false hands
    // capacity to the caller: raise once before the series, drop once after.

    @Test
    void defaultsManageCapacityToTrueSoASingleRunStillOwnsItsCapacity() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            """));
        assertThat(c.manageCapacity()).isTrue();
    }

    @Test
    void readsManageCapacityWhenSetAlongsideSkipLoad() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            skipLoad=true
            manageCapacity=false
            """));
        assertThat(c.manageCapacity()).isFalse();
    }

    @Test
    void rejectsManageCapacityFalseWithoutSkipLoadBecauseTheLoadWouldThrottle() throws Exception {
        // A load phase against a table nobody raised to loadWcu drives writes at a capacity
        // that does not exist. Better to refuse the config than to produce a throttled run.
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            manageCapacity=false
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manageCapacity");
    }

    // ---- provider ----------------------------------------------------------------------------

    @Test
    void defaultsToAwsSoEveryPreExistingConfigIsUnchanged() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-east-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            """));
        assertThat(c.provider()).isEqualTo(com.rickh.ddblat.provider.Provider.AWS);
    }

    @Test
    void acceptsAnOciConfiguration() throws Exception {
        Config c = Config.load(write("""
            table=t
            region=us-ashburn-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            provider=oci
            ociDatabaseOcid=ocid1.autonomousdatabase.oc1.iad.exampledb
            ociKeyFile=/home/x/.oci/keys.json
            """));
        assertThat(c.provider()).isEqualTo(com.rickh.ddblat.provider.Provider.OCI);
        assertThat(c.ociDatabaseOcid()).isEqualTo("ocid1.autonomousdatabase.oc1.iad.exampledb");
        assertThat(c.ociKeyFile().toString()).isEqualTo("/home/x/.oci/keys.json");
    }

    @Test
    void ociWithoutADatabaseOcidIsRejectedRatherThanProducingABadEndpoint() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-ashburn-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            provider=oci
            ociKeyFile=/home/x/.oci/keys.json
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ociDatabaseOcid");
    }

    @Test
    void ociWithoutAKeyFileIsRejected() throws Exception {
        assertThatThrownBy(() -> Config.load(write("""
            table=t
            region=us-ashburn-1
            itemCount=1024
            loadWcu=100
            readRcu=100
            resultsDir=/tmp
            provider=oci
            ociDatabaseOcid=ocid1.autonomousdatabase.oc1.iad.exampledb
            """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ociKeyFile");
    }
}
