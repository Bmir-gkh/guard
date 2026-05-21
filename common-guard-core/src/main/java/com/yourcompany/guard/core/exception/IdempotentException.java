package com.yourcompany.guard.core.exception;

/**
 * 幂等重复请求异常。
 */
public class IdempotentException extends RuntimeException {
    private final String key;
    private final String bizNo;

    public IdempotentException(String message, String key, String bizNo) {
        super(message);
        this.key = key;
        this.bizNo = bizNo;
    }

    public String getKey() {
        return key;
    }

    public String getBizNo() {
        return bizNo;
    }
}

