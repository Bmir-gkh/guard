package com.yourcompany.guard.core.aop;

import com.yourcompany.guard.annotations.IdempotentExceptionHandler;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

final class IdempotentHandlerResolver {
    private final BeanFactory beanFactory;

    IdempotentHandlerResolver(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    IdempotentExceptionHandler resolve(Class<? extends IdempotentExceptionHandler> type) {
        if (beanFactory != null) {
            try {
                return beanFactory.getBean(type);
            } catch (NoSuchBeanDefinitionException ignored) {
            }
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("无法创建幂等处理器: " + type.getName(), e);
        }
    }
}

