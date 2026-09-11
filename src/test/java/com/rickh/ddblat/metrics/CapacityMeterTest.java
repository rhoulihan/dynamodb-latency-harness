package com.rickh.ddblat.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;

class CapacityMeterTest {

    private static final long WINDOW_NANOS = 10_000_000_000L;   // 10 s
    private static final int  BUCKETS      = 100;               // 100 ms each
    private static final long BUCKET_NANOS = WINDOW_NANOS / BUCKETS;
    private static final long T0           = 1_000_000_000_000L;   // on a bucket boundary

    private CapacityMeter meter() {
        return new CapacityMeter(WINDOW_NANOS, BUCKETS);
    }

    @Test
    void constantFeedYieldsTheExpectedRate() {
        CapacityMeter m = meter();
        for (int i = 0; i < BUCKETS; i++) {
            m.record(T0 + i * BUCKET_NANOS, 50.0);              // 5000 CU over 10 s
        }
        assertThat(m.unitsPerSecond(T0 + (BUCKETS - 1) * BUCKET_NANOS)).isCloseTo(500.0, within(0.001));
    }

    @Test
    void recordsInTheSameBucketAccumulate() {
        CapacityMeter m = meter();
        m.record(T0, 13.0);
        m.record(T0 + 1_000_000L, 13.0);                        // same 100 ms bucket
        m.record(T0 + 50_000_000L, 24.0);                       // still the same bucket
        assertThat(m.unitsPerSecond(T0)).isCloseTo(5.0, within(0.001));   // 50 CU / 10 s
    }

    @Test
    void bucketsExpireOutOfTheWindow() {
        CapacityMeter m = meter();
        m.record(T0, 50.0);
        assertThat(m.unitsPerSecond(T0)).isCloseTo(5.0, within(0.001));
        assertThat(m.unitsPerSecond(T0 + WINDOW_NANOS - BUCKET_NANOS)).isCloseTo(5.0, within(0.001));
        assertThat(m.unitsPerSecond(T0 + WINDOW_NANOS)).isEqualTo(0.0);
        assertThat(m.unitsPerSecond(T0 + 2 * WINDOW_NANOS)).isEqualTo(0.0);
    }

    @Test
    void aPartiallyFilledWindowStillDividesByTheFullWindow() {
        CapacityMeter m = meter();
        for (int i = 0; i < 5; i++) {
            m.record(T0 + i * BUCKET_NANOS, 50.0);              // 250 CU in the last 500 ms
        }
        assertThat(m.unitsPerSecond(T0 + 4 * BUCKET_NANOS)).isCloseTo(25.0, within(0.001));
    }

    @Test
    void concurrentRecordFromEightThreadsTotalsCorrectly() throws Exception {
        CapacityMeter m = meter();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] workers = new Thread[8];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < 1_000; n++) m.record(T0, 2.5);
            });
            workers[i].start();
        }
        go.countDown();
        for (Thread w : workers) w.join();
        // 8 * 1000 * 2.5 = 20,000 CU over a 10 s window
        assertThat(m.unitsPerSecond(T0)).isCloseTo(2_000.0, within(0.001));
    }

    @Test
    void resetZeroesTheWindow() {
        CapacityMeter m = meter();
        for (int i = 0; i < BUCKETS; i++) m.record(T0 + i * BUCKET_NANOS, 50.0);
        m.reset();
        assertThat(m.unitsPerSecond(T0 + (BUCKETS - 1) * BUCKET_NANOS)).isEqualTo(0.0);
        m.record(T0 + (BUCKETS - 1) * BUCKET_NANOS, 10.0);
        assertThat(m.unitsPerSecond(T0 + (BUCKETS - 1) * BUCKET_NANOS)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void rejectsAWindowThatDoesNotDivideEvenlyIntoBuckets() {
        assertThatThrownBy(() -> new CapacityMeter(10_000_000_001L, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("divide evenly");
    }
}
