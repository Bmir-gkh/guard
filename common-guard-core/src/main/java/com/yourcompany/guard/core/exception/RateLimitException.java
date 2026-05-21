package com.yourcompany.guard.core.exception;

/**
 * 限流拒绝异常。
 */
public class RateLimitException extends RuntimeException {
    private final String key;

    public RateLimitException(String message, String key) {
        super(message);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}

