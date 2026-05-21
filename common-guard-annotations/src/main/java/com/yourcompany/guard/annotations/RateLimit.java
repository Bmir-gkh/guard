package com.yourcompany.guard.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    String key();

    long limit() default 10;

    long window() default 1;

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    String message() default "请求过于频繁";

    LimitAlgorithm algorithm() default LimitAlgorithm.FIXED_WINDOW;

    String fallback() default "";
}

