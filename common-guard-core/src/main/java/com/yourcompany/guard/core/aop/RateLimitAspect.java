package com.yourcompany.guard.core.aop;

import com.yourcompany.guard.annotations.RateLimit;
import com.yourcompany.guard.core.exception.GuardStoreException;
import com.yourcompany.guard.core.exception.RateLimitException;
import com.yourcompany.guard.core.fallback.FallbackMethodInvoker;
import com.yourcompany.guard.core.key.GuardKeyResolver;
import com.yourcompany.guard.core.log.GuardKeyLogUtil;
import com.yourcompany.guard.core.metrics.GuardMetrics;
import com.yourcompany.guard.store.api.GuardStore;
import com.yourcompany.guard.store.api.RateLimitRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;

@Aspect
@Order(1)
public class RateLimitAspect {
    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final GuardStore guardStore;
    private final GuardKeyResolver keyResolver;
    private final GuardMetrics metrics;
    private final RateLimitAspectConfig config;

    public RateLimitAspect(GuardStore guardStore, GuardKeyResolver keyResolver, GuardMetrics metrics, RateLimitAspectConfig config) {
        this.guardStore = guardStore;
        this.keyResolver = keyResolver;
        this.metrics = metrics;
        this.config = config;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Object target = pjp.getTarget();
        Object[] args = pjp.getArgs();

        // 1) 解析限流 key（带应用隔离前缀），避免不同应用/环境互相干扰
        String key = keyResolver.resolveKey(config.getKeyPrefix(), rateLimit.key(), method, args, target);
        RateLimitRequest request = new RateLimitRequest(key, rateLimit.limit(), rateLimit.window(), rateLimit.timeUnit(), rateLimit.algorithm());

        if (config.isLogEnabled()) {
            String displayKey = GuardKeyLogUtil.display(key, config.isLogRawKey(), config.getLogKeyMaxLength());
            String hash16 = GuardKeyLogUtil.hash16(key);
            log.info("guard.rate-limit key={} keyHash={} method={}#{} expr={} algorithm={} limit={} window={} {}",
                    displayKey,
                    hash16,
                    method.getDeclaringClass().getName(),
                    method.getName(),
                    rateLimit.key(),
                    rateLimit.algorithm(),
                    rateLimit.limit(),
                    rateLimit.window(),
                    rateLimit.timeUnit()
            );
        }

        // 2) 存储层执行限流算法：允许则放行；拒绝则 fallback 或抛异常
        boolean allowed;
        long start = System.nanoTime();
        try {
            allowed = guardStore.acquireRate(request);
        } catch (Exception e) {
            // 存储异常：可配置放行（默认）或拒绝（failOnError=true）
            metrics.recordStoreError("rate-limit.acquire");
            if (config.isFailOnError()) {
                metrics.recordRateLimitAcquire(Duration.ofNanos(System.nanoTime() - start), false);
                throw new GuardStoreException("限流存储异常", e);
            }
            allowed = true;
        }
        metrics.recordRateLimitAcquire(Duration.ofNanos(System.nanoTime() - start), !allowed);

        if (!allowed) {
            // 3) 被限流：优先执行同参 fallback 方法，避免统一抛异常影响业务体验
            if (rateLimit.fallback() != null && !rateLimit.fallback().isBlank()) {
                return FallbackMethodInvoker.invoke(target, method, args, rateLimit.fallback());
            }
            throw new RateLimitException(rateLimit.message(), key);
        }
        return pjp.proceed();
    }
}
