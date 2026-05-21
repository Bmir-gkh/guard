package com.yourcompany.guard.core.key;

import org.springframework.expression.ConstructorResolver;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.TypeLocator;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Collections;

/**
 * 通过 StandardEvaluationContext 禁用类型引用、构造器、方法调用，保留属性读取与变量访问。
 *
 * 目的：防止 SpEL 注入（例如 T(Runtime).getRuntime()、new ProcessBuilder()、#x.toString() 等）。
 * 注意：这里不是“完全不解析 SpEL”，而是将能力收缩到“读取变量/属性”，满足拼 key 的场景。
 */
final class SecureEvaluationContextFactory {
    private SecureEvaluationContextFactory() {
    }

    static StandardEvaluationContext create(Method method, Object[] args, Object target) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        // RootObject 一般是目标对象，允许通过属性读取（例如 #req.orderNo / #req.getOrderNo() 被禁用）
        context.setRootObject(target);

        context.setTypeLocator(new TypeLocator() {
            @Override
            public Class<?> findType(String typeName) {
                // 禁止 T(xxx) 类型引用
                throw new UnsupportedOperationException("禁止类型引用: " + typeName);
            }
        });
        // 禁止方法调用与构造器调用
        context.setMethodResolvers(Collections.<MethodResolver>emptyList());
        context.setConstructorResolvers(Collections.<ConstructorResolver>emptyList());

        return context;
    }
}
