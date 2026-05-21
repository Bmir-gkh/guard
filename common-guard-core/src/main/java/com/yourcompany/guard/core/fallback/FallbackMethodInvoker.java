package com.yourcompany.guard.core.fallback;

import java.lang.reflect.Method;

public final class FallbackMethodInvoker {
    private FallbackMethodInvoker() {
    }

    public static Object invoke(Object target, Method originalMethod, Object[] args, String fallbackMethodName) {
        if (target == null) {
            throw new IllegalStateException("目标对象为空，无法调用 fallback");
        }
        Class<?> type = target.getClass();
        Method fallback = findFallback(type, originalMethod, fallbackMethodName);
        try {
            fallback.setAccessible(true);
            return fallback.invoke(target, args);
        } catch (Exception e) {
            throw new IllegalStateException("fallback 调用失败: " + fallbackMethodName, e);
        }
    }

    private static Method findFallback(Class<?> type, Method originalMethod, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("fallback 方法名不能为空");
        }
        Class<?>[] paramTypes = originalMethod.getParameterTypes();
        try {
            return type.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            try {
                return type.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("未找到 fallback 方法: " + name, ex);
            }
        }
    }
}

