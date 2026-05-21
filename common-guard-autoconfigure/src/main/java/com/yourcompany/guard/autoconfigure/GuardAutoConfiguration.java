package com.yourcompany.guard.autoconfigure;

import com.yourcompany.guard.core.aop.IdempotentAspect;
import com.yourcompany.guard.core.aop.IdempotentAspectConfig;
import com.yourcompany.guard.core.aop.RateLimitAspect;
import com.yourcompany.guard.core.aop.RateLimitAspectConfig;
import com.yourcompany.guard.core.key.GuardKeyResolver;
import com.yourcompany.guard.core.key.SpelKeyResolver;
import com.yourcompany.guard.core.key.SpelKeyResolverOptions;
import com.yourcompany.guard.core.metrics.GuardMetrics;
import com.yourcompany.guard.core.metrics.NoopGuardMetrics;
import com.yourcompany.guard.store.api.GuardStore;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;

@AutoConfiguration
@ConditionalOnProperty(prefix = "common.guard", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GuardProperties.class)
public class GuardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GuardMetrics guardMetrics(ApplicationContext context) {
        // 指标采集是可选能力：只有存在 MeterRegistry Bean 时才启用 Micrometer 实现
        Object registry = getOptionalBean(context, "io.micrometer.core.instrument.MeterRegistry");
        if (registry == null) {
            return NoopGuardMetrics.INSTANCE;
        }
        try {
            Class<?> metricsType = ClassUtils.forName(
                    "com.yourcompany.guard.autoconfigure.MicrometerGuardMetrics",
                    context.getClassLoader()
            );
            return (GuardMetrics) metricsType
                    .getConstructor(requiredClass(context, "io.micrometer.core.instrument.MeterRegistry"))
                    .newInstance(registry);
        } catch (Exception e) {
            throw new IllegalStateException("创建 MicrometerGuardMetrics 失败", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public GuardKeyResolver guardKeyResolver(GuardProperties properties, Environment environment) {
        // appName 参与 key 前缀隔离：{appName}:{prefix}:{value}
        String appName = environment.getProperty("spring.application.name", "application");
        GuardProperties.SecurityProperties security = properties.getSecurity();
        SpelKeyResolverOptions options = new SpelKeyResolverOptions(
                appName,
                security.getExpressionMaxLength(),
                security.getKeyMaxLength(),
                security.getExpressionCacheSize()
        );
        return new SpelKeyResolver(options);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(
            GuardStore guardStore,
            GuardKeyResolver keyResolver,
            GuardMetrics metrics,
            GuardProperties properties,
            BeanFactory beanFactory
    ) {
        IdempotentAspectConfig config = new IdempotentAspectConfig(
                properties.getIdempotent().getKeyPrefix(),
                properties.getIdempotent().isFailOnError(),
                properties.getLog().isEnabled(),
                properties.getLog().isRawKey(),
                properties.getLog().getKeyMaxLength()
        );
        return new IdempotentAspect(guardStore, keyResolver, metrics, config, beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitAspect rateLimitAspect(
            GuardStore guardStore,
            GuardKeyResolver keyResolver,
            GuardMetrics metrics,
            GuardProperties properties
    ) {
        RateLimitAspectConfig config = new RateLimitAspectConfig(
                properties.getRateLimit().getKeyPrefix(),
                properties.getRateLimit().isFailOnError(),
                properties.getLog().isEnabled(),
                properties.getLog().isRawKey(),
                properties.getLog().getKeyMaxLength()
        );
        return new RateLimitAspect(guardStore, keyResolver, metrics, config);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "common.guard.store", havingValue = "local")
    @ConditionalOnClass(name = {
            "com.yourcompany.guard.store.caffeine.CaffeineGuardStore",
            "com.github.benmanes.caffeine.cache.Caffeine"
    })
    static class CaffeineStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public GuardStore caffeineGuardStore(GuardProperties properties, ApplicationContext context) {
            // local 模式：使用 Caffeine 本地实现
            return (GuardStore) newInstance(
                    context,
                    "com.yourcompany.guard.store.caffeine.CaffeineGuardStore",
                    new Class<?>[]{long.class, long.class},
                    new Object[]{
                            properties.getCaffeine().getMaxSize(),
                            properties.getCaffeine().getExpireAfterWriteSeconds()
                    }
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "common.guard.store", havingValue = "redisson")
    @ConditionalOnClass(name = {
            "com.yourcompany.guard.store.redisson.RedissonGuardStore",
            "org.redisson.api.RedissonClient"
    })
    static class RedissonStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public GuardStore redissonGuardStore(ApplicationContext context) {
            // redisson 模式：要求业务方自行提供 RedissonClient Bean
            Object redissonClient = getRequiredBean(context, "org.redisson.api.RedissonClient");
            return (GuardStore) newInstance(
                    context,
                    "com.yourcompany.guard.store.redisson.RedissonGuardStore",
                    new Class<?>[]{requiredClass(context, "org.redisson.api.RedissonClient")},
                    new Object[]{redissonClient}
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "common.guard.store", havingValue = "auto")
    static class AutoStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public GuardStore autoGuardStore(GuardProperties properties, ApplicationContext context) {
            // auto 模式优先级：RedissonClient 存在 -> Redisson；否则 Caffeine 可用 -> Caffeine；都不可用则启动失败
            Object redissonClient = getOptionalBean(context, "org.redisson.api.RedissonClient");
            if (redissonClient != null && ClassUtils.isPresent("com.yourcompany.guard.store.redisson.RedissonGuardStore", context.getClassLoader())) {
                return (GuardStore) newInstance(
                        context,
                        "com.yourcompany.guard.store.redisson.RedissonGuardStore",
                        new Class<?>[]{requiredClass(context, "org.redisson.api.RedissonClient")},
                        new Object[]{redissonClient}
                );
            }
            if (ClassUtils.isPresent("com.yourcompany.guard.store.caffeine.CaffeineGuardStore", context.getClassLoader())
                    && ClassUtils.isPresent("com.github.benmanes.caffeine.cache.Caffeine", context.getClassLoader())) {
                return (GuardStore) newInstance(
                        context,
                        "com.yourcompany.guard.store.caffeine.CaffeineGuardStore",
                        new Class<?>[]{long.class, long.class},
                        new Object[]{properties.getCaffeine().getMaxSize(), properties.getCaffeine().getExpireAfterWriteSeconds()}
                );
            }
            throw new IllegalStateException("GuardStore 未找到：请引入 common-guard-store-caffeine 或 common-guard-store-redisson，并正确配置 common.guard.store");
        }
    }

    private static Object getRequiredBean(ApplicationContext context, String className) {
        Object bean = getOptionalBean(context, className);
        if (bean == null) {
            throw new IllegalStateException("缺少 Bean: " + className);
        }
        return bean;
    }

    private static Object getOptionalBean(ApplicationContext context, String className) {
        try {
            Class<?> type = ClassUtils.forName(className, context.getClassLoader());
            return context.getBean(type);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Class<?> requiredClass(ApplicationContext context, String className) {
        try {
            return ClassUtils.forName(className, context.getClassLoader());
        } catch (Exception e) {
            throw new IllegalStateException("缺少类: " + className, e);
        }
    }

    private static Object newInstance(ApplicationContext context, String className, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> type = ClassUtils.forName(className, context.getClassLoader());
            Constructor<?> ctor = type.getConstructor(paramTypes);
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new IllegalStateException("创建实例失败: " + className, e);
        }
    }
}
