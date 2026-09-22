package com.jevguard.gate;

import com.jevguard.domain.Action;
import com.jevguard.domain.Direction;
import com.jevguard.domain.JevAnswers;
import com.jevguard.domain.RiskLevel;
import com.jevguard.rule.RiskRulesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GateLogic 7 条返回路径（PRD §4.5.1，M2 验收）：
 * 硬规则路径在 HardRuleEngine 测，这里覆盖门控的 6 步 7 出口。
 */
class GateLogicTest {

    private GateLogic gate;

    @BeforeEach
    void setUp() {
        RiskRulesConfig rules = new RiskRulesConfig();
        RiskRulesConfig.Gate g = new RiskRulesConfig.Gate();
        g.setPFreeze(0.80);
        g.setCHigh(0.70);
        g.setCLow(0.60);
        g.setHighRiskOrdinal(4);
        rules.setGate(g);
        gate = new GateLogic(rules);
    }

    private JevAnswers a(RiskLevel level, double rlConf, double shouldFreeze) {
        double fc = JevAnswers.deriveNoulConfidence(shouldFreeze);
        return new JevAnswers(level, rlConf, "normal", null, shouldFreeze, fc,
                "auto_pass", null, 0, 0, false);
    }

    @Test // 路径1：高风险+冻结建议+足够信度 → FREEZE
    void freezeWhenHighRiskAndFreezeAdvised() {
        var r = gate.route(Direction.WITHDRAW, a(RiskLevel.HIGH_RISK, 0.95, 0.90));
        assertEquals(Action.FREEZE, r.action());
    }

    @Test // 路径2a：高风险但建议不足 + 提现 → FREEZE（宁可误拦，审计 D3）
    void withdrawHighRiskLowConfidenceStillFreezes() {
        var r = gate.route(Direction.WITHDRAW, a(RiskLevel.CRITICAL, 0.50, 0.30));
        assertEquals(Action.FREEZE, r.action());
    }

    @Test // 路径2b：高风险但建议不足 + 充值 → MANUAL_REVIEW（入账已上链）
    void depositHighRiskGoesToReview() {
        var r = gate.route(Direction.DEPOSIT, a(RiskLevel.HIGH_RISK, 0.95, 0.50));
        assertEquals(Action.MANUAL_REVIEW, r.action());
    }

    @Test // 路径3：中风险 → MANUAL_REVIEW
    void suspiciousGoesToReview() {
        var r = gate.route(Direction.DEPOSIT, a(RiskLevel.SUSPICIOUS, 0.95, 0.10));
        assertEquals(Action.MANUAL_REVIEW, r.action());
    }

    @Test // 路径4：低风险但信度不足 → MANUAL_REVIEW
    void lowRiskLowConfidenceGoesToReview() {
        var r = gate.route(Direction.DEPOSIT, a(RiskLevel.NORMAL, 0.40, 0.05));
        assertEquals(Action.MANUAL_REVIEW, r.action());
    }

    @Test // 路径5：低风险高信度 → AUTO_PASS
    void normalPasses() {
        var r = gate.route(Direction.DEPOSIT, a(RiskLevel.NORMAL, 0.95, 0.02));
        assertEquals(Action.AUTO_PASS, r.action());
    }

    @Test // 边界：should_freeze 恰好在阈值上 → FREEZE（>= 语义）
    void freezeThresholdInclusive() {
        var r = gate.route(Direction.WITHDRAW, a(RiskLevel.HIGH_RISK, 0.95, 0.80));
        assertEquals(Action.FREEZE, r.action());
    }

    @Test // route 对照项绝不参与判定：route=auto_pass 但高风险+冻结建议 → 仍 FREEZE（审计 D1）
    void routeFieldNeverInfluencesDecision() {
        JevAnswers x = new JevAnswers(RiskLevel.CRITICAL, 0.99, "mixer_linked", null,
                0.95, JevAnswers.deriveNoulConfidence(0.95), "auto_pass", null, 0, 0, false);
        assertEquals(Action.FREEZE, gate.route(Direction.WITHDRAW, x).action());
    }
}
