package com.yourcompany.guard.store.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.yourcompany.guard.annotations.LimitAlgorithm;
import com.yourcompany.guard.store.api.GuardStore;
import com.yourcompany.guard.store.api.RateLimitRequest;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Caffeine 的本地实现。
 *
 * 适用场景：单机/小规模部署，或对一致性要求不高的限流；幂等建议在集群场景下优先使用 Redis/Redisson。
 *
 * 关键点：
 * - 幂等：利用 ConcurrentHashMap 的 putIfAbsent 原子语义
 * - 固定窗口：key = {业务key}:{窗口号}，窗口过期依赖 Caffeine 自动淘汰
 * - 令牌桶：每个 key 一个桶，按 period 进行 refill（实现为“每个周期补满一次”）
 */
public class CaffeineGuardStore implements GuardStore {
    private final Cache<String, ExpiringFlag> idempotentCache;
    private final Cache<String, ExpiringCounter> counterCache;
    private final Cache<String, TokenBucket> tokenBucketCache;

    public CaffeineGuardStore(long maxSize, long defaultExpireAfterWriteSeconds) {
        long safeMaxSize = maxSize <= 0 ? 10_000 : maxSize;
        long safeExpireSeconds = defaultExpireAfterWriteSeconds <= 0 ? 600 : defaultExpireAfterWriteSeconds;

        this.idempotentCache = Caffeine.newBuilder()
                .maximumSize(safeMaxSize)
                .expireAfter(new ExpiringFlagExpiry())
                .build();

        this.counterCache = Caffeine.newBuilder()
                .maximumSize(safeMaxSize)
                .expireAfter(new ExpiringCounterExpiry())
                .build();

        this.tokenBucketCache = Caffeine.newBuilder()
                .maximumSize(safeMaxSize)
                .expireAfterWrite(Duration.ofSeconds(safeExpireSeconds))
                .build();
    }

    @Override
    public boolean acquireIdempotent(String key, long ttl, TimeUnit unit) {
        Objects.requireNonNull(key, "key");
        long ttlNanos = toPositiveNanos(ttl, unit);
        ExpiringFlag value = new ExpiringFlag(System.nanoTime() + ttlNanos);
        // putIfAbsent 返回 null 表示首次抢占成功
        return idempotentCache.asMap().putIfAbsent(key, value) == null;
    }

    @Override
    public void releaseIdempotent(String key) {
        if (key == null) {
            return;
        }
        idempotentCache.invalidate(key);
    }

    @Override
    public boolean acquireRate(String key, long limit, long window, TimeUnit unit) {
        return acquireRate(new RateLimitRequest(key, limit, window, unit, LimitAlgorithm.FIXED_WINDOW));
    }

    @Override
    public boolean acquireRate(RateLimitRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.getLimit() <= 0) {
            return false;
        }
        if (request.getAlgorithm() == LimitAlgorithm.TOKEN_BUCKET) {
            return acquireTokenBucket(request);
        }
        return acquireFixedWindow(request);
    }

    private boolean acquireFixedWindow(RateLimitRequest request) {
        TimeUnit unit = request.getTimeUnit() == null ? TimeUnit.SECONDS : request.getTimeUnit();
        long windowNanos = toPositiveNanos(request.getWindow(), unit);
        long nowMillis = System.currentTimeMillis();
        long windowMillis = unit.toMillis(Math.max(1, request.getWindow()));
        long windowStart = windowMillis <= 0 ? nowMillis : (nowMillis / windowMillis);

        // 同一窗口内共享一个计数器；窗口变化自然生成新 key
        String windowKey = request.getKey() + ":" + windowStart;
        long expireAt = System.nanoTime() + windowNanos;
        ExpiringCounter counter = counterCache.get(windowKey, k -> new ExpiringCounter(new AtomicLong(0), expireAt));
        counter.expireAtNanos = expireAt;

        return counter.count.incrementAndGet() <= request.getLimit();
    }

    private boolean acquireTokenBucket(RateLimitRequest request) {
        TimeUnit unit = request.getTimeUnit() == null ? TimeUnit.SECONDS : request.getTimeUnit();
        long windowNanos = toPositiveNanos(request.getWindow(), unit);
        TokenBucket bucket = tokenBucketCache.get(request.getKey(), k -> new TokenBucket(request.getLimit(), windowNanos));
        synchronized (bucket) {
            bucket.refillIfNeeded(System.nanoTime());
            return bucket.tryAcquire();
        }
    }

    private static long toPositiveNanos(long value, TimeUnit unit) {
        if (unit == null) {
            unit = TimeUnit.SECONDS;
        }
        long v = Math.max(1, value);
        long nanos = unit.toNanos(v);
        return nanos <= 0 ? TimeUnit.SECONDS.toNanos(1) : nanos;
    }

    static final class ExpiringFlag {
        final long expireAtNanos;

        ExpiringFlag(long expireAtNanos) {
            this.expireAtNanos = expireAtNanos;
        }
    }

    static final class ExpiringFlagExpiry implements Expiry<String, ExpiringFlag> {
        @Override
        public long expireAfterCreate(String key, ExpiringFlag value, long currentTime) {
            return Math.max(1, value.expireAtNanos - currentTime);
        }

        @Override
        public long expireAfterUpdate(String key, ExpiringFlag value, long currentTime, long currentDuration) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(String key, ExpiringFlag value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    static final class ExpiringCounter {
        final AtomicLong count;
        volatile long expireAtNanos;

        ExpiringCounter(AtomicLong count, long expireAtNanos) {
            this.count = count;
            this.expireAtNanos = expireAtNanos;
        }
    }

    static final class ExpiringCounterExpiry implements Expiry<String, ExpiringCounter> {
        @Override
        public long expireAfterCreate(String key, ExpiringCounter value, long currentTime) {
            return Math.max(1, value.expireAtNanos - currentTime);
        }

        @Override
        public long expireAfterUpdate(String key, ExpiringCounter value, long currentTime, long currentDuration) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(String key, ExpiringCounter value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    static final class TokenBucket {
        final long capacity;
        final long refillPeriodNanos;
        long tokens;
        long lastRefillAtNanos;

        TokenBucket(long capacity, long refillPeriodNanos) {
            this.capacity = Math.max(1, capacity);
            this.refillPeriodNanos = Math.max(1, refillPeriodNanos);
            this.tokens = this.capacity;
            this.lastRefillAtNanos = System.nanoTime();
        }

        void refillIfNeeded(long nowNanos) {
            long elapsed = nowNanos - lastRefillAtNanos;
            if (elapsed <= 0) {
                return;
            }
            long periods = elapsed / refillPeriodNanos;
            if (periods <= 0) {
                return;
            }
            long refill = periods * capacity;
            tokens = Math.min(capacity, tokens + refill);
            lastRefillAtNanos = lastRefillAtNanos + periods * refillPeriodNanos;
        }

        boolean tryAcquire() {
            if (tokens <= 0) {
                return false;
            }
            tokens -= 1;
            return true;
        }
    }
}
