package com.yourcompany.guard.core.exception;

/**
 * 非法 SpEL 表达式：长度超限、解析失败等。
 */
public class IllegalExpressionException extends RuntimeException {
    public IllegalExpressionException(String message) {
        super(message);
    }

    public IllegalExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}

