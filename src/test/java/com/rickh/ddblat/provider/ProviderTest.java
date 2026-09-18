package com.rickh.ddblat.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderTest {

    @Test void defaultsToAwsSoEveryExistingConfigIsUnchanged() {
        assertThat(Provider.parse(null)).isEqualTo(Provider.AWS);
        assertThat(Provider.parse("")).isEqualTo(Provider.AWS);
    }

    @Test void acceptsTheNamesAnOperatorWouldActuallyType() {
        assertThat(Provider.parse("aws")).isEqualTo(Provider.AWS);
        assertThat(Provider.parse("DynamoDB")).isEqualTo(Provider.AWS);
        assertThat(Provider.parse("oci")).isEqualTo(Provider.OCI);
        assertThat(Provider.parse(" Oracle ")).isEqualTo(Provider.OCI);
        assertThat(Provider.parse("adb")).isEqualTo(Provider.OCI);
    }

    @Test void rejectsAnUnknownProviderRatherThanDefaultingToAws() {
        // Silently falling back to AWS would run the whole test against the wrong service.
        assertThatThrownBy(() -> Provider.parse("azure"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("aws, oci or scylla");
    }

    @Test void awsReportsCapacityAndHasCloudWatch() {
        assertThat(Provider.AWS.reportsConsumedCapacity()).isTrue();
        assertThat(Provider.AWS.hasCloudWatch()).isTrue();
        assertThat(Provider.AWS.canVerifySizeModel()).isTrue();
    }

    @Test void ociReportsNoCapacitySoItsChecksAreNotApplicableRatherThanPassing() {
        assertThat(Provider.OCI.reportsConsumedCapacity()).isFalse();
        assertThat(Provider.OCI.hasCloudWatch()).isFalse();
        assertThat(Provider.OCI.canVerifySizeModel()).isFalse();
    }

    @Test void bothProvidersBindToTheSameItemCeilingSoPayloadsAreIdentical() {
        // Oracle accepted 450 KiB in testing; the comparison needs identical payloads, so the
        // stricter limit governs both.
        assertThat(Provider.OCI.maxItemBytes()).isEqualTo(Provider.AWS.maxItemBytes());
    }

    @Test
    void ociSignsWithUsWest2RegardlessOfWhereTheDatabaseLives() {
        // Oracle's endpoint validates the SigV4 credential scope and accepts ONLY us-west-2.
        // Measured against an ADB in us-ashburn-1: every other region, including the database's
        // own, returned 401 Invalid credential. Undocumented, so pinned by this test.
        assertThat(Provider.OCI.signingRegion()).isEqualTo("us-west-2");
    }

    @Test
    void awsSignsWithWhateverRegionWasConfigured() {
        assertThat(Provider.AWS.signingRegion()).isNull();
    }

    @Test
    void awsHasADistinctEventuallyConsistentReadPath() {
        assertThat(Provider.AWS.hasEventuallyConsistentReads()).isTrue();
    }

    @Test
    void ociHasNoEventuallyConsistentReadSoThePhaseMustBeSkipped() {
        // ConsistentRead=false on Autonomous AI Database returns the same strongly consistent
        // result over the same path. Running the phase would re-measure R-A under a misleading
        // label AND charge 7.5 capacity units for work that cost 15, inflating achieved
        // throughput by 2x.
        assertThat(Provider.OCI.hasEventuallyConsistentReads()).isFalse();
    }

    // ---- ScyllaDB Alternator --------------------------------------------------------------

    @Test
    void parsesScyllaByTheNamesAnOperatorWouldType() {
        assertThat(Provider.parse("scylla")).isEqualTo(Provider.SCYLLA);
        assertThat(Provider.parse("ScyllaDB")).isEqualTo(Provider.SCYLLA);
        assertThat(Provider.parse(" alternator ")).isEqualTo(Provider.SCYLLA);
    }

    @Test
    void scyllaHasNoProvisionedCapacityToTarget() {
        // Alternator accepts BillingMode and ProvisionedThroughput but ignores them, behaving
        // like PAY_PER_REQUEST with no per-table cap. There is nothing to set, nothing to take
        // 90% of, and nothing to reset at teardown.
        assertThat(Provider.SCYLLA.hasProvisionedCapacity()).isFalse();
        assertThat(Provider.AWS.hasProvisionedCapacity()).isTrue();
        assertThat(Provider.OCI.hasProvisionedCapacity()).isTrue();
    }

    @Test
    void scyllaEnforcesNoThrottlingSoThatCriterionCannotBeEarned() {
        // "Throttle events do not occur in Alternator because per-table throughput limits are
        // not enforced." A validity criterion that always passes is worse than no criterion --
        // it certifies something it never checked -- so this must read NOT APPLICABLE.
        assertThat(Provider.SCYLLA.enforcesThrottling()).isFalse();
        assertThat(Provider.AWS.enforcesThrottling()).isTrue();
        assertThat(Provider.OCI.enforcesThrottling()).isTrue();
    }

    @Test
    void scyllaHasARealEventuallyConsistentReadPath() {
        // Unlike Oracle: eventually-consistent reads use LOCAL_ONE, strongly-consistent use
        // LOCAL_QUORUM. They are genuinely different operations, so the R-B phase is meaningful.
        assertThat(Provider.SCYLLA.hasEventuallyConsistentReads()).isTrue();
    }

    @Test
    void scyllaReportsNoConsumedCapacitySoTheSizeModelCarriesIt() {
        assertThat(Provider.SCYLLA.reportsConsumedCapacity()).isFalse();
        assertThat(Provider.SCYLLA.canVerifySizeModel()).isFalse();
        assertThat(Provider.SCYLLA.hasCloudWatch()).isFalse();
    }

    @Test
    void scyllaSignsWithTheConfiguredRegionLikeAws() {
        // Standard SigV4. Only Oracle pins a region the service demands.
        assertThat(Provider.SCYLLA.signingRegion()).isNull();
    }

    @Test
    void everyProviderBindsToTheSameItemCeiling() {
        assertThat(Provider.SCYLLA.maxItemBytes()).isEqualTo(Provider.AWS.maxItemBytes());
    }
}
