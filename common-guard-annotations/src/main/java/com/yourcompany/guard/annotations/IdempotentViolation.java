package com.yourcompany.guard.annotations;

/**
 * 幂等拦截命中时提供给处理器的最小信息。
 */
public final class IdempotentViolation {
    private final String key;
    private final String bizNo;
    private final String message;

    public IdempotentViolation(String key, String bizNo, String message) {
        this.key = key;
        this.bizNo = bizNo;
        this.message = message;
    }

    public String getKey() {
        return key;
    }

    public String getBizNo() {
        return bizNo;
    }

    public String getMessage() {
        return message;
    }
}

