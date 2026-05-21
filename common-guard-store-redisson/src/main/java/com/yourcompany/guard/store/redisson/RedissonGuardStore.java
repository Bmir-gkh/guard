package com.yourcompany.guard.store.redisson;

import com.yourcompany.guard.annotations.LimitAlgorithm;
import com.yourcompany.guard.store.api.GuardStore;
import com.yourcompany.guard.store.api.RateLimitRequest;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的实现。
 *
 * 关键点：
 * - 幂等：RBucket.trySet + TTL，保证“首次写入成功”语义，且自动过期避免脏数据
 * - 固定窗口：使用 RAtomicLong 计数器，窗口首次计数时设置 TTL
 * - 令牌桶：使用 Redisson 的 RRateLimiter
 */
public class RedissonGuardStore implements GuardStore {
    private final RedissonClient redissonClient;

    public RedissonGuardStore(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
    }

    @Override
    public boolean acquireIdempotent(String key, long ttl, TimeUnit unit) {
        RBucket<String> bucket = redissonClient.getBucket(key);
        long safeTtl = Math.max(1, ttl);
        TimeUnit safeUnit = unit == null ? TimeUnit.SECONDS : unit;
        // trySet：只在 key 不存在时设置成功，天然满足幂等抢占需求
        return bucket.trySet("1", safeTtl, safeUnit);
    }

    @Override
    public void releaseIdempotent(String key) {
        if (key == null) {
            return;
        }
        redissonClient.getBucket(key).delete();
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
        long windowMs = safeToMillis(request.getWindow(), request.getTimeUnit());
        long windowIndex = windowMs <= 0 ? System.currentTimeMillis() : (System.currentTimeMillis() / windowMs);
        String windowKey = request.getKey() + ":" + windowIndex;

        RAtomicLong counter = redissonClient.getAtomicLong(windowKey);
        long count = counter.incrementAndGet();
        if (count == 1) {
            // 只在首次计数时设置 TTL，避免每次请求都刷新过期时间导致“滑动窗口”效果
            counter.expire(Math.max(1, request.getWindow()), request.getTimeUnit() == null ? TimeUnit.SECONDS : request.getTimeUnit());
        }
        return count <= request.getLimit();
    }

    private boolean acquireTokenBucket(RateLimitRequest request) {
        long windowMs = safeToMillis(request.getWindow(), request.getTimeUnit());
        long intervalMs = Math.max(1, windowMs);

        RRateLimiter limiter = redissonClient.getRateLimiter(request.getKey() + ":tb");
        limiter.trySetRate(RateType.OVERALL, request.getLimit(), intervalMs, RateIntervalUnit.MILLISECONDS);
        return limiter.tryAcquire(1);
    }

    private static long safeToMillis(long value, TimeUnit unit) {
        long v = Math.max(1, value);
        TimeUnit u = unit == null ? TimeUnit.SECONDS : unit;
        long ms = u.toMillis(v);
        return ms <= 0 ? 1 : ms;
    }
}
