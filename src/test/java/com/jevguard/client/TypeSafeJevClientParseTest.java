package com.jevguard.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevguard.domain.JevAnswers;
import com.jevguard.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 解析实测响应形状（docs/05-m1-findings.md）：
 * noul 无 confidence → 派生；score 的 probabilities 0 基索引 → +1 序数。
 */
class TypeSafeJevClientParseTest {

    private static final String REAL_SHAPE = """
            {"model":"jev-1.13.0","answers":{
              "should_freeze":{"type":"noul","noul":0.91},
              "risk_level":{"type":"score","score":3.93,"confidence":0.94,
                 "legend":{"0":"normal","1":"watch","2":"suspicious","3":"high_risk","4":"critical"},
                 "probabilities":{"0":0.0,"1":0.0,"2":0.0,"3":0.06,"4":0.94}},
              "pattern":{"type":"choice","choice":"mixer_linked","confidence":0.92,
                 "probabilities":{"normal":0.01,"mixer_linked":0.92}},
              "route":{"type":"choice","choice":"freeze","confidence":0.88}
            },"usage":{"input_tokens":395,"output_tokens":36}}""";

    @Test
    void parsesRealShape() throws Exception {
        TypeSafeJevClient c = new TypeSafeJevClient("http://localhost", "k", "jev-latest", 1000);
        JevAnswers a = c.parse(new ObjectMapper().readTree(REAL_SHAPE), 123, false);

        assertEquals(RiskLevel.CRITICAL, a.riskLevel());      // 0基 argmax=4 → 序数5
        assertEquals(0.94, a.riskLevelConfidence(), 1e-6);
        assertEquals(0.91, a.shouldFreeze(), 1e-6);
        assertEquals(0.82, a.freezeConfidence(), 1e-6);       // |0.91-0.5|*2 派生
        assertEquals("mixer_linked", a.pattern());
        assertEquals("freeze", a.route());
        assertEquals(395, a.inputTokens());
        assertFalse(a.retried());
    }
}
