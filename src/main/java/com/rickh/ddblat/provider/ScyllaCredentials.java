package com.rickh.ddblat.provider;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the credentials ScyllaDB Alternator signs requests with.
 *
 * Alternator reuses ScyllaDB's own role system rather than issuing separate API keys: the access
 * key ID is a <em>role name</em>, and the secret is that role's {@code salted_hash}, obtained over
 * CQL rather than from any HTTP API:
 *
 * <pre>
 *   CREATE ROLE ddblat WITH PASSWORD = '...' AND LOGIN = true;
 *   SELECT salted_hash FROM system.roles WHERE role = 'ddblat';
 * </pre>
 *
 * That is why provisioning this provider needs a CQL client in the path, where AWS needs nothing
 * and Oracle needs a REST call. Once the two values are on disk, though, they are an ordinary
 * static credentials pair and SigV4 proceeds normally — unlike Oracle, no signing region has to
 * be forced.
 *
 * Parsed, never logged. The salted_hash is password-equivalent: anyone holding it can sign as
 * that role.
 */
public final class ScyllaCredentials {

    private static final Pattern ACCESS_KEY = field("access_key_id");
    private static final Pattern SECRET_KEY = field("secret_access_key");

    private ScyllaCredentials() {}

    private static Pattern field(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"");
    }

    /** @throws IOException if the file is unreadable, malformed, or missing either field. */
    public static AwsCredentialsProvider fromKeyFile(Path file) throws IOException {
        String json = Files.readString(file);
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(require(json, ACCESS_KEY, "access_key_id", file),
                                       require(json, SECRET_KEY, "secret_access_key", file)));
    }

    private static String require(String json, Pattern p, String name, Path file) {
        Matcher m = p.matcher(json);
        if (!m.find() || m.group(1).isBlank()) {
            // Names the field and the file, never the contents.
            throw new IllegalArgumentException(
                "no '" + name + "' in " + file + " -- expected a JSON object carrying the "
                + "Alternator role name as access_key_id and its salted_hash as "
                + "secret_access_key");
        }
        return m.group(1);
    }

    /**
     * Rejects an endpoint the SDK would fail on obscurely.
     *
     * ScyllaDB Cloud presents node addresses without a scheme, so pasting one straight into
     * config is the likely mistake; the SDK's error for a schemeless endpoint does not point at
     * the cause.
     */
    public static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("scyllaEndpoint is required");
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException(
                "scyllaEndpoint must start with http:// or https://, got: " + endpoint
                + " -- ScyllaDB Cloud shows node addresses without a scheme, so add one "
                + "(Alternator's default ports are 8000 plain and 8043 TLS)");
        }
        URI.create(endpoint);          // throws on anything still malformed
    }
}
