package com.jevguard.domain;

/**
 * Jev 返回的 4 个问题答案（已解析）。
 * 实测（docs/05-m1-findings.md）：noul 不返回 confidence，
 * 因此 freezeConfidence 由 |noul-0.5|*2 在客户端派生。
 */
public record JevAnswers(
        RiskLevel riskLevel,
        double riskLevelConfidence,
        String pattern,
        Double patternConfidence,   // 可空，防御上游变动
        double shouldFreeze,        // 0-1 概率
        double freezeConfidence,    // 派生：|shouldFreeze - 0.5| * 2
        String route,               // 对照项，不参与路由
        Double routeConfidence,
        long inputTokens,
        long latencyMs,
        boolean retried
) {
    public static double deriveNoulConfidence(double noul) {
        return Math.abs(noul - 0.5) * 2.0;
    }
}
