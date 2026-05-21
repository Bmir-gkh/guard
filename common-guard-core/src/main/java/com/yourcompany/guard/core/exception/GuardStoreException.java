package com.yourcompany.guard.core.exception;

/**
 * 存储层异常（如 Redis 超时、序列化异常等）。
 */
public class GuardStoreException extends RuntimeException {
    public GuardStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

