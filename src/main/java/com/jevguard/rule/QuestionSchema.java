package com.jevguard.rule;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Jev 问题 schema 构建（ScreeningService 与 EvalRunner 共用）。
 * 含一处规范化：Spring Binder 把 YAML 序列绑进 Map<String,Object> 时会变成
 * {"0":..,"1":..} 索引 Map（实测，docs/05-m1-findings.md §3），
 * 上游要求 score.criteria 是真 List，这里按数字键排序还原。
 * Jackson/snakeyaml 直接加载时不会触发该问题，但幂等无害。
 */
public final class QuestionSchema {

    private QuestionSchema() {}

    public static Map<String, Object> build(RiskRulesConfig rules) {
        Map<String, Object> q = new HashMap<>();
        rules.getQuestions().forEach((k, v) -> {
            Map<String, Object> item = new HashMap<>(v);
            Object criteria = item.get("criteria");
            if (criteria instanceof Map<?, ?> m && isIndexedMap(m)) {
                item.put("criteria", indexedMapToList(m));
            }
            q.put(k, item);
        });
        return q;
    }

    private static boolean isIndexedMap(Map<?, ?> m) {
        if (m.isEmpty()) return false;
        for (Object key : m.keySet()) {
            if (!(key instanceof String s) || !s.matches("\\d+")) return false;
        }
        return true;
    }

    private static List<Object> indexedMapToList(Map<?, ?> m) {
        return m.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> Integer.parseInt((String) e.getKey())))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }
}
