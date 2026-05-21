package com.yourcompany.guard.store.api;

import com.yourcompany.guard.annotations.LimitAlgorithm;

import java.util.concurrent.TimeUnit;

/**
 * 限流请求的扩展参数封装。
 */
public final class RateLimitRequest {
    private final String key;
    private final long limit;
    private final long window;
    private final TimeUnit timeUnit;
    private final LimitAlgorithm algorithm;

    public RateLimitRequest(String key, long limit, long window, TimeUnit timeUnit, LimitAlgorithm algorithm) {
        this.key = key;
        this.limit = limit;
        this.window = window;
        this.timeUnit = timeUnit;
        this.algorithm = algorithm;
    }

    public String getKey() {
        return key;
    }

    public long getLimit() {
        return limit;
    }

    public long getWindow() {
        return window;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public LimitAlgorithm getAlgorithm() {
        return algorithm;
    }
}

