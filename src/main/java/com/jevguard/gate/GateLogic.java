package com.jevguard.gate;

import com.jevguard.domain.Action;
import com.jevguard.domain.Direction;
import com.jevguard.domain.JevAnswers;
import com.jevguard.rule.RiskRulesConfig;
import org.springframework.stereotype.Component;

/**
 * 门控逻辑（PRD §4.5.1）。
 * 注意：should_freeze 的答案值本身是 0-1 概率（比 P_FREEZE）；
 * confidence[should_freeze] 是客户端派生信度（比 C_HIGH）；
 * 主门控用 confidence[risk_level]，不做四者平均（审计 B2）。
 * route 是对照项，绝不参与这里的判定（审计 D1）。
 */
@Component
public class GateLogic {

    private final RiskRulesConfig.Gate g;

    public GateLogic(RiskRulesConfig rules) {
        this.g = rules.getGate();
    }

    public record GateResult(Action action, String reason) {}

    public GateResult route(Direction direction, JevAnswers a) {
        int ord = a.riskLevel().ordinal;

        // 1. 高风险 + 冻结建议 + 足够信度 → 冻结
        if (ord >= g.getHighRiskOrdinal()
                && a.shouldFreeze() >= g.getPFreeze()
                && a.freezeConfidence() >= g.getCHigh()) {
            return new GateResult(Action.FREEZE,
                    "high_risk(ord=%d) + should_freeze=%.2f>=%.2f + freezeConf=%.2f>=%.2f"
                            .formatted(ord, a.shouldFreeze(), g.getPFreeze(), a.freezeConfidence(), g.getCHigh()));
        }

        // 2. 高风险但信度/建议不足 → 按方向分叉（审计 D3：提现宁可误拦，不能降级人审）
        if (ord >= g.getHighRiskOrdinal()) {
            return direction == Direction.WITHDRAW
                    ? new GateResult(Action.FREEZE, "high_risk withdraw: freeze rather than downgrade (irreversible once broadcast)")
                    : new GateResult(Action.MANUAL_REVIEW, "high_risk deposit: already on-chain, mark account for review");
        }

        // 3. 中风险 → 人审
        if (ord == 3) {
            return new GateResult(Action.MANUAL_REVIEW, "suspicious(ord=3)");
        }

        // 4. 低风险但主问题信度不足 → 人审（不确定就升级）
        if (a.riskLevelConfidence() < g.getCLow()) {
            return new GateResult(Action.MANUAL_REVIEW,
                    "low confidence[risk_level]=%.2f<%.2f".formatted(a.riskLevelConfidence(), g.getCLow()));
        }

        // 5. 其余放行
        return new GateResult(Action.AUTO_PASS, "risk_level=%s conf=%.2f".formatted(a.riskLevel().label, a.riskLevelConfidence()));
    }
}
