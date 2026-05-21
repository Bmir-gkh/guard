package com.yourcompany.guard.autoconfigure;

import com.yourcompany.guard.core.metrics.GuardMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

public final class MicrometerGuardMetrics implements GuardMetrics {
    private final Timer idempotentTimer;
    private final Counter idempotentDuplicate;
    private final Timer rateLimitTimer;
    private final Counter rateLimitRejected;
    private final Counter storeError;

    public MicrometerGuardMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        this.idempotentTimer = registry.timer("guard.idempotent.acquire.time");
        this.idempotentDuplicate = registry.counter("guard.idempotent.duplicate");
        this.rateLimitTimer = registry.timer("guard.rate-limit.acquire.time");
        this.rateLimitRejected = registry.counter("guard.rate-limit.rejected");
        this.storeError = registry.counter("guard.store.error");
    }

    @Override
    public void recordIdempotentAcquire(Duration cost, boolean duplicate) {
        if (cost != null) {
            idempotentTimer.record(cost);
        }
        if (duplicate) {
            idempotentDuplicate.increment();
        }
    }

    @Override
    public void recordRateLimitAcquire(Duration cost, boolean rejected) {
        if (cost != null) {
            rateLimitTimer.record(cost);
        }
        if (rejected) {
            rateLimitRejected.increment();
        }
    }

    @Override
    public void recordStoreError(String operation) {
        storeError.increment();
    }
}

