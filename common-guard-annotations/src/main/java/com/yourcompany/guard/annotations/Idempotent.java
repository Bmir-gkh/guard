package com.yourcompany.guard.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String key();

    long expire() default 5;

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    String message() default "重复请求";

    String bizNo() default "";

    Class<? extends IdempotentExceptionHandler> handler() default DefaultIdempotentExceptionHandler.class;

    OnException onException() default OnException.DELETE_KEY;
}

