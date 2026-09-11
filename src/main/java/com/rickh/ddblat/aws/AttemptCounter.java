package com.rickh.ddblat.aws;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/**
 * Counts HTTP attempts per call on the calling thread. A masked retry can otherwise
 * inflate a P99.9 invisibly, so every request records how many attempts it took.
 * The int[] is allocated once per thread, so counting costs no allocation.
 */
public final class AttemptCounter implements ExecutionInterceptor {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    @Override
    public void beforeTransmission(Context.BeforeTransmission ctx, ExecutionAttributes attrs) {
        COUNT.get()[0]++;
    }

    public static void reset()     { COUNT.get()[0] = 0; }
    public static int  attempts()  { return COUNT.get()[0]; }
}
