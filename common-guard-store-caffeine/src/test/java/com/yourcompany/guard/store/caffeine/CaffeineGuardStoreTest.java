package com.yourcompany.guard.store.caffeine;

import com.yourcompany.guard.annotations.LimitAlgorithm;
import com.yourcompany.guard.store.api.RateLimitRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaffeineGuardStoreTest {

    @Test
    void idempotentAcquireAndRelease() {
        CaffeineGuardStore store = new CaffeineGuardStore(10_000, 600);
        assertTrue(store.acquireIdempotent("k1", 5, TimeUnit.SECONDS));
        assertFalse(store.acquireIdempotent("k1", 5, TimeUnit.SECONDS));

        store.releaseIdempotent("k1");
        assertTrue(store.acquireIdempotent("k1", 5, TimeUnit.SECONDS));
    }

    @Test
    void fixedWindowRateLimit() {
        CaffeineGuardStore store = new CaffeineGuardStore(10_000, 600);
        String key = "rl";
        assertTrue(store.acquireRate(key, 2, 60, TimeUnit.SECONDS));
        assertTrue(store.acquireRate(key, 2, 60, TimeUnit.SECONDS));
        assertFalse(store.acquireRate(key, 2, 60, TimeUnit.SECONDS));
    }

    @Test
    void tokenBucketRateLimit() {
        CaffeineGuardStore store = new CaffeineGuardStore(10_000, 600);
        RateLimitRequest request = new RateLimitRequest("tb", 2, 60, TimeUnit.SECONDS, LimitAlgorithm.TOKEN_BUCKET);
        assertTrue(store.acquireRate(request));
        assertTrue(store.acquireRate(request));
        assertFalse(store.acquireRate(request));
    }
}

