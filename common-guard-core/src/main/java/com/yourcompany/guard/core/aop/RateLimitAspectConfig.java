package com.yourcompany.guard.core.aop;

public final class RateLimitAspectConfig {
    private final String keyPrefix;
    private final boolean failOnError;
    private final boolean logEnabled;
    private final boolean logRawKey;
    private final int logKeyMaxLength;

    public RateLimitAspectConfig(String keyPrefix, boolean failOnError, boolean logEnabled, boolean logRawKey, int logKeyMaxLength) {
        this.keyPrefix = keyPrefix == null ? "rl:" : keyPrefix;
        this.failOnError = failOnError;
        this.logEnabled = logEnabled;
        this.logRawKey = logRawKey;
        this.logKeyMaxLength = logKeyMaxLength <= 0 ? 256 : logKeyMaxLength;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    public boolean isLogEnabled() {
        return logEnabled;
    }

    public boolean isLogRawKey() {
        return logRawKey;
    }

    public int getLogKeyMaxLength() {
        return logKeyMaxLength;
    }
}
