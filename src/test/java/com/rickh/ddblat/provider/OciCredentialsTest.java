package com.rickh.ddblat.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class OciCredentialsTest {

    @TempDir Path tmp;

    private Path write(String body) throws Exception {
        Path p = tmp.resolve("keys.json");
        Files.writeString(p, body);
        return p;
    }

    @Test void readsTheKeyFileOracleActuallyReturns() throws Exception {
        var creds = OciCredentials.fromKeyFile(write("""
            {"access_key_id":"ak_abc123","secret_access_key":"c2VjcmV0","
             expiration_time":"2026-09-12T08:01:57Z",
             "permissions":{"dynamodb_api_permissions":[{"actions":["ADMIN_ANY"]}]}}
            """)).resolveCredentials();
        assertThat(creds.accessKeyId()).isEqualTo("ak_abc123");
        assertThat(creds.secretAccessKey()).isEqualTo("c2VjcmV0");
    }

    @Test void failsLoudlyRatherThanSilentlyUsingABlankSecret() throws Exception {
        // An empty secret would produce a confusing auth error much later, from the SDK.
        assertThatThrownBy(() -> OciCredentials.fromKeyFile(
                write("{\"access_key_id\":\"ak_abc\",\"secret_access_key\":\"\"}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("secret_access_key");
    }

    @Test void theErrorNamesTheFieldAndFileButNeverTheContents() throws Exception {
        Path p = write("{\"access_key_id\":\"ak_LEAKME\"}");
        assertThatThrownBy(() -> OciCredentials.fromKeyFile(p))
            .hasMessageContaining("secret_access_key")
            .hasMessageContaining(p.toString())
            .hasMessageNotContaining("ak_LEAKME");
    }

    @Test void buildsTheKeyValueStoreEndpoint() {
        assertThat(OciCredentials.endpointFor("us-ashburn-1",
                "ocid1.autonomousdatabase.oc1.iad.exampledb"))
            .isEqualTo("https://dataaccess.adb.us-ashburn-1.oraclecloudapps.com"
                     + "/adb/keyvaluestore/v1/ocid1.autonomousdatabase.oc1.iad.exampledb");
    }

    @Test void rejectsAnOcidThatIsNotAnAutonomousDatabase() {
        // Pointing the harness at a compartment or instance OCID would fail obscurely at runtime.
        assertThatThrownBy(() -> OciCredentials.endpointFor("us-ashburn-1", "ocid1.compartment.oc1..aaa"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Autonomous Database OCID");
    }
}
