package com.rickh.ddblat.aws;

import java.time.Duration;

/** Global Constraints, in assertable form. */
public record ClientSettings(
        int maxConnections,
        Duration connectionTtl,
        Duration connectionMaxIdle,
        Duration apiCallTimeout,
        Duration apiCallAttemptTimeout,
        int numRetries) {

    public static ClientSettings forThreads(int threads) {
        return new ClientSettings(
            threads * 2,
            Duration.ZERO,                 // never recycle: a mid-window handshake is a fake tail sample
            Duration.ofMinutes(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            2);
    }
}
