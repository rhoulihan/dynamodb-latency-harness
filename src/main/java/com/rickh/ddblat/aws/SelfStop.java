package com.rickh.ddblat.aws;

import software.amazon.awssdk.services.ec2.Ec2Client;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/**
 * The harness stops its own instance at teardown so the Mac does not have to stay awake
 * polling for two and a half hours. The instance profile grants ec2:StopInstances on
 * exactly one resource ARN -- this instance.
 */
public final class SelfStop {

    private SelfStop() {}

    public static String instanceId() {
        try {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> token = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://169.254.169.254/latest/api/token"))
                .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> id = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://169.254.169.254/latest/meta-data/instance-id"))
                .header("X-aws-ec2-metadata-token", token.body())
                .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return id.body();
        } catch (Exception e) {
            throw new IllegalStateException("not running on EC2, or IMDSv2 unreachable", e);
        }
    }

    public static void stop(Ec2Client ec2, String instanceId) {
        ec2.stopInstances(r -> r.instanceIds(instanceId));
    }
}
