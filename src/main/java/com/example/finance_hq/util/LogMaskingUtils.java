package com.example.finance_hq.util;

public final class LogMaskingUtils {

    private LogMaskingUtils() {}

    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "[masked]";
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        if (local.isEmpty()) {
            return "[masked]";
        }
        return local.charAt(0) + "***" + email.substring(at);
    }
}
