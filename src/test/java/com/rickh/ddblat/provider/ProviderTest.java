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
            .hasMessageContaining("aws or oci");
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
}
