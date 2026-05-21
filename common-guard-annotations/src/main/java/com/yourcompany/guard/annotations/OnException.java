package com.yourcompany.guard.annotations;

/**
 * 业务异常时幂等 Key 的处理策略。
 */
public enum OnException {
    /**
     * 删除 key（允许后续重试）。
     */
    DELETE_KEY,
    /**
     * 保留 key（保持幂等保护，直到过期）。
     */
    KEEP_KEY
}

