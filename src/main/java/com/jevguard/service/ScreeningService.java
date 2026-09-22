package com.jevguard.service;

import com.jevguard.client.JevClient;
import com.jevguard.client.TypeSafeJevClient.JevUnavailableException;
import com.jevguard.domain.Action;
import com.jevguard.domain.Decision;
import com.jevguard.domain.Features;
import com.jevguard.domain.JevAnswers;
import com.jevguard.domain.TransferEvent;
import com.jevguard.feature.FeatureBuilder;
import com.jevguard.gate.GateLogic;
import com.jevguard.log.DecisionLog;
import com.jevguard.rule.HardRuleEngine;
import com.jevguard.rule.QuestionSchema;
import com.jevguard.rule.RiskRulesConfig;
import com.jevguard.state.StateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 主链路（PRD §3）：
 * 事件 → 特征 → 硬规则(一票否决) → state 渲染 → Jev 分诊 → 门控 → 日志。
 * 上游失败降级 MANUAL_REVIEW（宁可人审，不可漏放，PRD §5.2）。
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final FeatureBuilder featureBuilder;
    private final HardRuleEngine hardRules;
    private final StateRenderer stateRenderer;
    private final JevClient jev;
    private final GateLogic gate;
    private final DecisionLog decisionLog;
    private final RiskRulesConfig rules;

    public ScreeningService(FeatureBuilder featureBuilder, HardRuleEngine hardRules,
                            StateRenderer stateRenderer, JevClient jev, GateLogic gate,
                            DecisionLog decisionLog, RiskRulesConfig rules) {
        this.featureBuilder = featureBuilder;
        this.hardRules = hardRules;
        this.stateRenderer = stateRenderer;
        this.jev = jev;
        this.gate = gate;
        this.decisionLog = decisionLog;
        this.rules = rules;
    }

    public Decision screen(TransferEvent event) {
        long t0 = System.nanoTime();

        long f0 = System.nanoTime();
        Features features = featureBuilder.build(event);
        long featureMs = (System.nanoTime() - f0) / 1_000_000;

        // 0. 硬规则一票否决（不调 Jev）
        HardRuleEngine.HardRuleHit hit = hardRules.check(event);
        if (hit != null) {
            Decision d = build(event, features, hit.rule(), null, hit.action(),
                    "hard rule: " + hit.rule(), featureMs, 0, t0, false);
            decisionLog.append(d);
            return d;
        }

        // 1-2. state 渲染 + Jev 分诊（失败降级）
        String state = stateRenderer.render(event, features);
        JevAnswers answers;
        boolean degraded = false;
        long jevMs;
        try {
            answers = jev.evaluate(state, questionSchema());
            jevMs = answers.latencyMs();
        } catch (JevUnavailableException e) {
            log.warn("Jev unavailable, degrade to MANUAL_REVIEW: {}", e.getMessage());
            answers = null;
            degraded = true;
            jevMs = (System.nanoTime() - f0) / 1_000_000 - featureMs;
        }

        // 3. 门控
        Action action;
        String reason;
        if (degraded) {
            action = Action.MANUAL_REVIEW;
            reason = "degraded: upstream unavailable";
        } else {
            GateLogic.GateResult r = gate.route(event.direction(), answers);
            action = r.action();
            reason = r.reason();
        }

        Decision d = build(event, features, null, answers, action, reason, featureMs, jevMs, t0, degraded);
        decisionLog.append(d);
        return d;
    }

    private Decision build(TransferEvent e, Features f, String ruleHit, JevAnswers jev,
                           Action action, String reason, long featureMs, long jevMs, long t0, boolean degraded) {
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        return new Decision(UUID.randomUUID().toString(), Instant.now(), e, f, ruleHit,
                jev, action, reason, featureMs, jevMs, totalMs, degraded);
    }

    /** risk-rules.yml 的 4 个问题 schema（构建逻辑见 QuestionSchema，EvalRunner 共用）。 */
    private Map<String, Object> questionSchema() {
        return QuestionSchema.build(rules);
    }

    public boolean isMockMode() {
        return jev.isMock();
    }
}
