package com.yourcompany.guard.core.metrics;

import java.time.Duration;

public final class NoopGuardMetrics implements GuardMetrics {
    public static final NoopGuardMetrics INSTANCE = new NoopGuardMetrics();

    private NoopGuardMetrics() {
    }

    @Override
    public void recordIdempotentAcquire(Duration cost, boolean duplicate) {
    }

    @Override
    public void recordRateLimitAcquire(Duration cost, boolean rejected) {
    }

    @Override
    public void recordStoreError(String operation) {
    }
}

