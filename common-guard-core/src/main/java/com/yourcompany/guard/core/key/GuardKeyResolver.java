package com.yourcompany.guard.core.key;

import java.lang.reflect.Method;

public interface GuardKeyResolver {

    /**
     * 解析 Key。
     * @param prefix      Key 前缀
     * @param expression  Key 表达式
     * @param method      目标方法
     * @param args        目标方法参数
     * @param target      目标对象
     * @return 解析后的 Key
     */
    String resolveKey(String prefix, String expression, Method method, Object[] args, Object target);

    /**
     * 解析 Key 的表达式。
     * @param expression  Key 表达式
     * @param method      目标方法
     * @param args        目标方法参数
     * @param target      目标对象
     * @return 解析后的 Key
     */
    String evaluate(String expression, Method method, Object[] args, Object target);
}

