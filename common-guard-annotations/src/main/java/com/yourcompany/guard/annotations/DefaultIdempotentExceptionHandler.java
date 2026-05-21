package com.yourcompany.guard.annotations;

/**
 * 默认处理器：直接抛出 RuntimeException。
 */
public class DefaultIdempotentExceptionHandler implements IdempotentExceptionHandler {
    @Override
    public RuntimeException handle(IdempotentViolation violation) {
        return new RuntimeException(violation.getMessage());
    }
}

