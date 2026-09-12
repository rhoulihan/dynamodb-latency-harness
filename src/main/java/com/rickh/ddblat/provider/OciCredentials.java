package com.rickh.ddblat.provider;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the access key Oracle mints for the DynamoDB-compatible API.
 *
 * The key is created by POSTing to {@code /adb/auth/v1/databases/{ocid}/accesskeys} with the
 * database user's credentials, and the response is a JSON document holding an access key id and
 * secret in AWS's own shape -- so once read, it is an ordinary static credentials provider.
 *
 * Deliberately parsed, never logged. The harness reads this file the same way the AWS side
 * reads its credential CSV: values go straight into the SDK and are never printed, so a run's
 * console output and uploaded artifacts cannot leak them.
 */
public final class OciCredentials {

    private static final Pattern ACCESS_KEY = field("access_key_id");
    private static final Pattern SECRET_KEY = field("secret_access_key");

    private OciCredentials() {}

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
                "no '" + name + "' in " + file + " -- expected the JSON returned by "
                + "POST /adb/auth/v1/databases/{ocid}/accesskeys");
        }
        return m.group(1);
    }

    /**
     * Builds the key-value store endpoint for an Autonomous Database.
     * Shape: {@code https://dataaccess.adb.{region}.oraclecloudapps.com/adb/keyvaluestore/v1/{ocid}}
     */
    public static String endpointFor(String region, String databaseOcid) {
        if (region == null || region.isBlank()) throw new IllegalArgumentException("region required");
        if (databaseOcid == null || !databaseOcid.startsWith("ocid1.autonomousdatabase.")) {
            throw new IllegalArgumentException(
                "expected an Autonomous Database OCID, got: " + databaseOcid);
        }
        return "https://dataaccess.adb." + region + ".oraclecloudapps.com"
             + "/adb/keyvaluestore/v1/" + databaseOcid;
    }
}
