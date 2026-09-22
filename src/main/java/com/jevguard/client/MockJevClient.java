package com.jevguard.client;

import com.jevguard.domain.JevAnswers;
import com.jevguard.domain.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Mock 客户端：无 TYPESAFE_API_KEY 时启用（PRD §4.8）。
 * 按 state 内容返回确定性 fixture 答案，clone 即可跑通全流程。
 * 注意：mock 下跑校准评估会被 EvalRunner 硬失败拦截（PRD §4.7.4）——
 * 否则陌生人 clone 后没有 key 也能得到一份"完美"报告，比没有报告更伤信誉。
 */
public class MockJevClient implements JevClient {

    private static final Logger log = LoggerFactory.getLogger(MockJevClient.class);

    public MockJevClient() {
        log.warn("TYPESAFE_API_KEY not set — running in MOCK mode. Answers are deterministic fixtures, not real AI decisions.");
    }

    @Override
    public JevAnswers evaluate(String state, Map<String, Object> questions) {
        long t0 = System.nanoTime();
        String s = state.toLowerCase();

        RiskLevel level;
        double conf;
        String pattern;
        double freeze;
        String route;

        if (s.contains("mixer")) {
            level = RiskLevel.CRITICAL; conf = 0.97; pattern = "mixer_linked"; freeze = 0.95; route = "freeze";
        } else if (s.contains("same_amount_burst_within_1h: yes")) {
            level = RiskLevel.HIGH_RISK; conf = 0.9; pattern = "structuring"; freeze = 0.85; route = "freeze";
        } else if (s.contains("minutes_since_last_transfer: 1") || s.contains("minutes_since_last_transfer: 2")) {
            level = RiskLevel.HIGH_RISK; conf = 0.88; pattern = "rapid_in_out"; freeze = 0.8; route = "manual_review";
        } else if (s.contains("address_age_days: 0") && s.contains("transfers_24h_count: 1")) {
            level = RiskLevel.SUSPICIOUS; conf = 0.75; pattern = "new_address_burst"; freeze = 0.3; route = "manual_review";
        } else if (s.contains("amount_band: dust")) {
            level = RiskLevel.WATCH; conf = 0.8; pattern = "dust_probe"; freeze = 0.05; route = "auto_pass";
        } else {
            level = RiskLevel.NORMAL; conf = 0.95; pattern = "normal"; freeze = 0.02; route = "auto_pass";
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new JevAnswers(level, conf, pattern, conf, freeze,
                JevAnswers.deriveNoulConfidence(freeze), route, conf, 0, ms, false);
    }

    @Override
    public boolean isMock() { return true; }
}
