package com.rickh.ddblat.aws;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class ClientSettingsTest {

    @Test
    void connectionPoolIsTwicetheThreadCountSoAcquisitionNeverBlocks() {
        assertThat(ClientSettings.forThreads(32).maxConnections()).isEqualTo(64);
        assertThat(ClientSettings.forThreads(256).maxConnections()).isEqualTo(512);
    }

    @Test
    void connectionsAreNeverRecycledMidPhase() {
        ClientSettings s = ClientSettings.forThreads(32);
        assertThat(s.connectionTtl()).isEqualTo(Duration.ZERO);
        assertThat(s.connectionMaxIdle()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void timeoutsBoundAHungWorkerWellAboveAnyPlausibleTail() {
        ClientSettings s = ClientSettings.forThreads(32);
        assertThat(s.apiCallTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(s.apiCallAttemptTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void retryPolicyIsExplicitNotTheSdkDefault() {
        assertThat(ClientSettings.forThreads(32).numRetries()).isEqualTo(2);
    }
}
