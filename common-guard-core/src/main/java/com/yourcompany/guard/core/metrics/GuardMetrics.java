package com.yourcompany.guard.core.metrics;

import java.time.Duration;

public interface GuardMetrics {
    void recordIdempotentAcquire(Duration cost, boolean duplicate);

    void recordRateLimitAcquire(Duration cost, boolean rejected);

    void recordStoreError(String operation);
}

