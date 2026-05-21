package com.yourcompany.guard.core.aop;

import com.yourcompany.guard.annotations.Idempotent;
import com.yourcompany.guard.annotations.IdempotentExceptionHandler;
import com.yourcompany.guard.annotations.IdempotentViolation;
import com.yourcompany.guard.annotations.OnException;
import com.yourcompany.guard.core.exception.GuardStoreException;
import com.yourcompany.guard.core.exception.IdempotentException;
import com.yourcompany.guard.core.key.GuardKeyResolver;
import com.yourcompany.guard.core.log.GuardKeyLogUtil;
import com.yourcompany.guard.core.metrics.GuardMetrics;
import com.yourcompany.guard.store.api.GuardStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;

@Aspect
@Order(0)
public class IdempotentAspect {
    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final GuardStore guardStore;
    private final GuardKeyResolver keyResolver;
    private final GuardMetrics metrics;
    private final IdempotentAspectConfig config;
    private final IdempotentHandlerResolver handlerResolver;

    public IdempotentAspect(
            GuardStore guardStore,
            GuardKeyResolver keyResolver,
            GuardMetrics metrics,
            IdempotentAspectConfig config,
            BeanFactory beanFactory
    ) {
        this.guardStore = guardStore;
        this.keyResolver = keyResolver;
        this.metrics = metrics;
        this.config = config;
        this.handlerResolver = new IdempotentHandlerResolver(beanFactory);
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Object target = pjp.getTarget();
        Object[] args = pjp.getArgs();

        // 1) 解析 key：包含应用名隔离前缀，避免多应用冲突
        String key = keyResolver.resolveKey(config.getKeyPrefix(), idempotent.key(), method, args, target);
        // 2) 可选解析业务单号，便于业务方日志或错误返回使用
        String bizNo = idempotent.bizNo() == null || idempotent.bizNo().isBlank()
                ? ""
                : keyResolver.evaluate(idempotent.bizNo(), method, args, target);

        if (config.isLogEnabled()) {
            String displayKey = GuardKeyLogUtil.display(key, config.isLogRawKey(), config.getLogKeyMaxLength());
            String hash16 = GuardKeyLogUtil.hash16(key);
            log.info("guard.idempotent key={} keyHash={} bizNo={} method={}#{} expr={}",
                    displayKey,
                    hash16,
                    bizNo,
                    method.getDeclaringClass().getName(),
                    method.getName(),
                    idempotent.key()
            );
        }

        // 3) 原子抢占幂等 key：成功才执行目标方法
        boolean acquired;
        long start = System.nanoTime();
        try {
            acquired = guardStore.acquireIdempotent(key, idempotent.expire(), idempotent.timeUnit());
        } catch (Exception e) {
            // 存储异常：可配置放行（默认）或拒绝（failOnError=true），防止雪崩/误杀
            metrics.recordStoreError("idempotent.acquire");
            if (config.isFailOnError()) {
                metrics.recordIdempotentAcquire(Duration.ofNanos(System.nanoTime() - start), false);
                throw new GuardStoreException("幂等存储异常", e);
            }
            acquired = true;
        }
        metrics.recordIdempotentAcquire(Duration.ofNanos(System.nanoTime() - start), !acquired);

        if (!acquired) {
            // 4) 重复请求：优先走自定义处理器，否则抛出默认异常
            IdempotentExceptionHandler handler = handlerResolver.resolve(idempotent.handler());
            IdempotentViolation violation = new IdempotentViolation(key, bizNo, idempotent.message());
            RuntimeException ex = handler == null ? null : handler.handle(violation);
            if (ex != null) {
                throw ex;
            }
            throw new IdempotentException(idempotent.message(), key, bizNo);
        }

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            // 5) 业务异常：按策略决定是否删除幂等 key（允许业务重试）
            if (idempotent.onException() == OnException.DELETE_KEY) {
                try {
                    guardStore.releaseIdempotent(key);
                } catch (Exception ignored) {
                }
            }
            throw t;
        }
    }
}
