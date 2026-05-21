package com.yourcompany.guard.annotations;

/**
 * 幂等重复请求处理器：返回要抛出的异常（不返回则由框架抛默认异常）。
 */
@FunctionalInterface
public interface IdempotentExceptionHandler {
    RuntimeException handle(IdempotentViolation violation);
}

