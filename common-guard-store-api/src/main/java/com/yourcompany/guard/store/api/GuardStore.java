package com.yourcompany.guard.store.api;

import java.util.concurrent.TimeUnit;

public interface GuardStore {
    /**
     * 幂等原子设置。
     *
     * @return true 首次设置成功，false 表示重复
     */
    boolean acquireIdempotent(String key, long ttl, TimeUnit unit);

    /**
     * 释放幂等 key（业务异常时）。
     */
    void releaseIdempotent(String key);

    /**
     * 限流获取许可（默认固定窗口）。
     */
    boolean acquireRate(String key, long limit, long window, TimeUnit unit);

    /**
     * 带扩展参数的限流（算法、预热等）。
     */
    boolean acquireRate(RateLimitRequest request);

    /**
     * 清理本地资源。
     */
    default void cleanUp() {
    }
}

