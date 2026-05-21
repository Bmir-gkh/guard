package com.yourcompany.guard.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "common.guard")
public class GuardProperties {
    private boolean enabled = true;

    /**
     * auto/local/redisson
     */
    private String store = "auto";

    private IdempotentProperties idempotent = new IdempotentProperties();
    private RateLimitProperties rateLimit = new RateLimitProperties();
    private CaffeineProperties caffeine = new CaffeineProperties();
    private RedissonProperties redisson = new RedissonProperties();
    private SecurityProperties security = new SecurityProperties();
    private LogProperties log = new LogProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public IdempotentProperties getIdempotent() {
        return idempotent;
    }

    public void setIdempotent(IdempotentProperties idempotent) {
        this.idempotent = Objects.requireNonNull(idempotent);
    }

    public RateLimitProperties getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimitProperties rateLimit) {
        this.rateLimit = Objects.requireNonNull(rateLimit);
    }

    public CaffeineProperties getCaffeine() {
        return caffeine;
    }

    public void setCaffeine(CaffeineProperties caffeine) {
        this.caffeine = Objects.requireNonNull(caffeine);
    }

    public RedissonProperties getRedisson() {
        return redisson;
    }

    public void setRedisson(RedissonProperties redisson) {
        this.redisson = Objects.requireNonNull(redisson);
    }

    public SecurityProperties getSecurity() {
        return security;
    }

    public void setSecurity(SecurityProperties security) {
        this.security = Objects.requireNonNull(security);
    }

    public LogProperties getLog() {
        return log;
    }

    public void setLog(LogProperties log) {
        this.log = Objects.requireNonNull(log);
    }

    public static class IdempotentProperties {
        private String keyPrefix = "idem:";
        private boolean failOnError = false;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isFailOnError() {
            return failOnError;
        }

        public void setFailOnError(boolean failOnError) {
            this.failOnError = failOnError;
        }
    }

    public static class RateLimitProperties {
        private String keyPrefix = "rl:";
        private boolean failOnError = false;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isFailOnError() {
            return failOnError;
        }

        public void setFailOnError(boolean failOnError) {
            this.failOnError = failOnError;
        }
    }

    public static class CaffeineProperties {
        private long maxSize = 10_000;
        private long expireAfterWriteSeconds = 600;

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }

        public long getExpireAfterWriteSeconds() {
            return expireAfterWriteSeconds;
        }

        public void setExpireAfterWriteSeconds(long expireAfterWriteSeconds) {
            this.expireAfterWriteSeconds = expireAfterWriteSeconds;
        }
    }

    public static class RedissonProperties {
    }

    public static class SecurityProperties {
        /**
         * 表达式长度上限，避免恶意长 Key。
         */
        private int expressionMaxLength = 256;

        /**
         * 最终 key 的长度上限。
         */
        private int keyMaxLength = 512;

        /**
         * SpEL 解析缓存大小上限。
         */
        private int expressionCacheSize = 1000;

        public int getExpressionMaxLength() {
            return expressionMaxLength;
        }

        public void setExpressionMaxLength(int expressionMaxLength) {
            this.expressionMaxLength = expressionMaxLength;
        }

        public int getKeyMaxLength() {
            return keyMaxLength;
        }

        public void setKeyMaxLength(int keyMaxLength) {
            this.keyMaxLength = keyMaxLength;
        }

        public int getExpressionCacheSize() {
            return expressionCacheSize;
        }

        public void setExpressionCacheSize(int expressionCacheSize) {
            this.expressionCacheSize = expressionCacheSize;
        }
    }

    public static class LogProperties {
        /**
         * 是否输出 key 日志（默认关闭，避免泄露敏感信息与日志膨胀）。
         */
        private boolean enabled = false;

        /**
         * 是否输出原始 key（开启后可能包含 token 等敏感信息，需谨慎）。
         */
        private boolean rawKey = false;

        /**
         * 日志里 key 的最大输出长度，超过会截断。
         */
        private int keyMaxLength = 256;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRawKey() {
            return rawKey;
        }

        public void setRawKey(boolean rawKey) {
            this.rawKey = rawKey;
        }

        public int getKeyMaxLength() {
            return keyMaxLength;
        }

        public void setKeyMaxLength(int keyMaxLength) {
            this.keyMaxLength = keyMaxLength;
        }
    }
}
