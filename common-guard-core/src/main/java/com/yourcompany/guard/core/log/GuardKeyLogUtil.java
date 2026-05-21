package com.yourcompany.guard.core.log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class GuardKeyLogUtil {
    private GuardKeyLogUtil() {
    }

    public static String display(String key, boolean raw, int maxLength) {
        if (key == null) {
            return "";
        }
        String value = raw ? key : mask(key);
        return truncate(value, maxLength);
    }

    public static String hash16(String key) {
        if (key == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(bytes);
            return hex.length() <= 16 ? hex : hex.substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String value, int maxLength) {
        int max = maxLength <= 0 ? 256 : maxLength;
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...(len=" + value.length() + ")";
    }

    private static String mask(String key) {
        if (key == null) {
            return "";
        }
        int len = key.length();
        if (len <= 16) {
            return "***";
        }
        int head = Math.min(16, len);
        int tail = Math.min(6, Math.max(0, len - head));
        String prefix = key.substring(0, head);
        String suffix = tail == 0 ? "" : key.substring(len - tail);
        return prefix + "***" + suffix;
    }
}

