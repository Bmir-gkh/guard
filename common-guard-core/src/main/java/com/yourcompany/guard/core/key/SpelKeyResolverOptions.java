package com.yourcompany.guard.core.key;

/**
 * SpEL Key 解析相关的安全与性能配置。
 */
public final class SpelKeyResolverOptions {
    private final String appName;
    private final int expressionMaxLength;
    private final int keyMaxLength;
    private final int expressionCacheSize;

    public SpelKeyResolverOptions(String appName, int expressionMaxLength, int keyMaxLength, int expressionCacheSize) {
        this.appName = appName == null || appName.isBlank() ? "application" : appName;
        this.expressionMaxLength = expressionMaxLength;
        this.keyMaxLength = keyMaxLength;
        this.expressionCacheSize = expressionCacheSize;
    }

    public String getAppName() {
        return appName;
    }

    public int getExpressionMaxLength() {
        return expressionMaxLength;
    }

    public int getKeyMaxLength() {
        return keyMaxLength;
    }

    public int getExpressionCacheSize() {
        return expressionCacheSize;
    }
}

