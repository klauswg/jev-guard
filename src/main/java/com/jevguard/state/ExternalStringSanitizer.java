package com.jevguard.state;

import org.springframework.stereotype.Component;

/**
 * 外部可控字符串清洗（PRD §4.4a，审计 D4）。
 * 链上 token 名、对手方标签都是攻击者可控的任意字符串，
 * 必须在进入 state 前过白名单 + 截断 + 剥控制字符。
 */
@Component
public class ExternalStringSanitizer {

    private static final int ADDR_MAX = 42;
    private static final int TOKEN_MAX = 12;

    public String address(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^0-9a-fA-Fx]", "");
        return s.isEmpty() ? null : truncate(s, ADDR_MAX);
    }

    public String token(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^A-Za-z0-9._-]", "");
        return s.isEmpty() ? null : truncate(s, TOKEN_MAX);
    }

    /** counterpartyLabel 只接受枚举，不透传任意字符串 */
    public String label(String raw) {
        if (raw == null) return "unknown";
        return switch (raw) {
            case "mixer", "exchange", "sanctioned" -> raw;
            default -> "unknown";
        };
    }

    /** 通用：剥换行与控制字符 */
    public String stripControl(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("[\\p{Cntrl}]", " ").trim();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
