package com.jevguard.eval;

import com.jevguard.domain.Action;
import com.jevguard.domain.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** PRD §4.7.1 配额纪律：报告印出的 n 必须恰好等于 100 = 72 + 18 + 10。 */
class SampleGeneratorTest {

    @Test
    void quotaIsExactly100() {
        List<LabeledSample> samples = new SampleGenerator().generate();
        assertEquals(100, samples.size());

        Map<String, Long> byCategory = samples.stream()
                .collect(Collectors.groupingBy(LabeledSample::category, Collectors.counting()));
        assertEquals(72L, byCategory.get("pattern"));
        assertEquals(18L, byCategory.get("boundary"));
        assertEquals(10L, byCategory.get("injection"));

        Map<String, Long> byPattern = samples.stream()
                .filter(s -> s.category().equals("pattern"))
                .collect(Collectors.groupingBy(LabeledSample::pattern, Collectors.counting()));
        assertEquals(6, byPattern.size());
        byPattern.values().forEach(n -> assertEquals(12L, n));
    }

    @Test
    void everySampleHasExpectedActionAndId() {
        for (LabeledSample s : new SampleGenerator().generate()) {
            assertNotNull(s.id());
            assertNotNull(s.expectedAction());
            assertNotNull(s.event());
        }
    }

    @Test
    void deterministicAcrossRuns() {
        List<LabeledSample> a = new SampleGenerator().generate();
        List<LabeledSample> b = new SampleGenerator().generate();
        assertEquals(a.stream().map(LabeledSample::id).toList(),
                b.stream().map(LabeledSample::id).toList());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).event().amountUsd(), b.get(i).event().amountUsd());
        }
    }

    @Test
    void injectionSamplesCarryHostilePayloadButKeepUnderlyingLabel() {
        List<LabeledSample> injections = new SampleGenerator().generate().stream()
                .filter(s -> s.category().equals("injection")).toList();
        assertEquals(10, injections.size());
        // 底层正常单基准 AUTO_PASS，底层恶意单基准 FREEZE——注入不得改变基准
        assertEquals(5, injections.stream().filter(s -> s.expectedAction() == Action.AUTO_PASS).count());
        assertEquals(5, injections.stream().filter(s -> s.expectedAction() == Action.FREEZE).count());
        assertTrue(injections.stream().anyMatch(s -> s.event().token().length() > 100), "截断回归样本存在");
        assertTrue(injections.stream().anyMatch(s -> s.event().token().contains("IGNORE")), "指令注入样本存在");
    }

    @Test
    void labelingPolicyMapping() {
        assertEquals(Action.AUTO_PASS, SampleGenerator.expectedFor("normal", Direction.WITHDRAW));
        assertEquals(Action.MANUAL_REVIEW, SampleGenerator.expectedFor("dust_probe", Direction.DEPOSIT));
        assertEquals(Action.MANUAL_REVIEW, SampleGenerator.expectedFor("structuring", Direction.DEPOSIT));
        assertEquals(Action.FREEZE, SampleGenerator.expectedFor("structuring", Direction.WITHDRAW));
        assertEquals(Action.FREEZE, SampleGenerator.expectedFor("rapid_in_out", Direction.WITHDRAW));
        assertEquals(Action.FREEZE, SampleGenerator.expectedFor("mixer_linked", Direction.DEPOSIT));
    }
}
