package com.rickh.ddblat.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ScyllaCredentialsTest {

    @TempDir Path tmp;

    private Path write(String body) throws Exception {
        Path p = tmp.resolve("keys.json");
        Files.writeString(p, body);
        return p;
    }

    @Test
    void readsTheRoleNameAndSaltedHash() throws Exception {
        // Alternator's access key ID is a role name and its secret is that role's salted_hash,
        // read out of system.roles over CQL. Once read they are ordinary static credentials.
        var creds = ScyllaCredentials.fromKeyFile(write("""
            {"access_key_id":"ddblat",
             "secret_access_key":"$6$rounds=5000$abcdefgh$0123456789"}
            """)).resolveCredentials();
        assertThat(creds.accessKeyId()).isEqualTo("ddblat");
        assertThat(creds.secretAccessKey()).isEqualTo("$6$rounds=5000$abcdefgh$0123456789");
    }

    @Test
    void failsLoudlyRatherThanSigningWithABlankSecret() throws Exception {
        assertThatThrownBy(() -> ScyllaCredentials.fromKeyFile(
                write("{\"access_key_id\":\"ddblat\",\"secret_access_key\":\"\"}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("secret_access_key");
    }

    @Test
    void theErrorNamesTheFieldAndFileButNeverTheContents() throws Exception {
        Path p = write("{\"access_key_id\":\"LEAKME\"}");
        assertThatThrownBy(() -> ScyllaCredentials.fromKeyFile(p))
            .hasMessageContaining("secret_access_key")
            .hasMessageContaining(p.toString())
            .hasMessageNotContaining("LEAKME");
    }

    @Test
    void rejectsAnEndpointThatIsNotAUrl() {
        // A bare host is the likely mistake: ScyllaDB Cloud shows node addresses without scheme,
        // and the SDK's failure for a schemeless endpoint is obscure.
        assertThatThrownBy(() -> ScyllaCredentials.validateEndpoint("node-0.scylladb.com:8043"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http");
    }

    @Test
    void acceptsHttpAndHttpsEndpoints() {
        assertThatCode(() -> {
            ScyllaCredentials.validateEndpoint("https://node-0.scylladb.com:8043");
            ScyllaCredentials.validateEndpoint("http://10.20.1.5:8000");
        }).doesNotThrowAnyException();
    }
}
