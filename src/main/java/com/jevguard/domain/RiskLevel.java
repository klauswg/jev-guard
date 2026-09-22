package com.jevguard.domain;

import java.util.Map;

/**
 * risk_level 序数映射（PRD §4.4.1）。
 * 注意：上游 score 的 legend 是 0 基索引（实测 2026-09-23，docs/05-m1-findings.md），
 * Java 侧统一用 1 基序数：normal=1, watch=2, suspicious=3, high_risk=4, critical=5。
 */
public enum RiskLevel {
    NORMAL(1, "normal"),
    WATCH(2, "watch"),
    SUSPICIOUS(3, "suspicious"),
    HIGH_RISK(4, "high_risk"),
    CRITICAL(5, "critical");

    public final int ordinal;
    public final String label;

    RiskLevel(int ordinal, String label) {
        this.ordinal = ordinal;
        this.label = label;
    }

    private static final Map<String, RiskLevel> BY_LABEL = Map.of(
            "normal", NORMAL, "watch", WATCH, "suspicious", SUSPICIOUS,
            "high_risk", HIGH_RISK, "critical", CRITICAL);

    public static RiskLevel fromLabel(String label) {
        RiskLevel r = BY_LABEL.get(label);
        if (r == null) throw new IllegalArgumentException("unknown risk_level label: " + label);
        return r;
    }

    /** 上游 0 基 legend 索引 → 1 基序数 */
    public static RiskLevel fromZeroBasedIndex(int idx) {
        return values()[Math.max(0, Math.min(idx, values().length - 1))];
    }
}
