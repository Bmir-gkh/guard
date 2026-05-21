package com.yourcompany.guard.annotations;

/**
 * 限流算法类型。
 */
public enum LimitAlgorithm {
    /**
     * 固定窗口计数。
     */
    FIXED_WINDOW,
    /**
     * 令牌桶。
     */
    TOKEN_BUCKET
}

