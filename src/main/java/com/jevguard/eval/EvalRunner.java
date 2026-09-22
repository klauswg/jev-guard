package com.jevguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevguard.client.JevClient;
import com.jevguard.client.MockJevClient;
import com.jevguard.client.TypeSafeJevClient;
import com.jevguard.domain.Action;
import com.jevguard.domain.Features;
import com.jevguard.domain.JevAnswers;
import com.jevguard.domain.TransferEvent;
import com.jevguard.feature.FeatureBuilder;
import com.jevguard.gate.GateLogic;
import com.jevguard.rule.HardRuleEngine;
import com.jevguard.rule.QuestionSchema;
import com.jevguard.rule.RiskRulesConfig;
import com.jevguard.state.ExternalStringSanitizer;
import com.jevguard.state.StateRenderer;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 校准评估器（PRD §4.7，M4 第一优先级是基线对照）。
 *
 * 用法：
 *   mvn compile exec:java -Dexec.mainClass=com.jevguard.eval.EvalRunner
 *   参数：--allow-mock（mock 强制跑，产物打 MOCK- 前缀与水印）
 *         --regenerate（重新生成 eval/labeled-samples.jsonl）
 *
 * 纪律（审计 B4）：mock 模式默认硬失败——MockJevClient 返回确定性 fixture，
 * mock 下的"校准报告"必然接近满分，流出去就是假证据。
 */
public class EvalRunner {

    private static final Path SAMPLES = Path.of("eval", "labeled-samples.jsonl");
    private static final Path REPORT = Path.of("docs", "calibration-report.md");
    private static final String BASE_URL = "https://api.typesafe.ai/v1/systemone";
    private static final int TIMEOUT_MS = 8000;

    public static void main(String[] args) throws Exception {
        boolean allowMock = hasArg(args, "--allow-mock");
        boolean regenerate = hasArg(args, "--regenerate");

        // 1. 规则与组件（不走 Spring，直接绑定同一份 risk-rules.yml）
        RiskRulesConfig rules = loadRules();
        FeatureBuilder featureBuilder = new FeatureBuilder(rules);
        HardRuleEngine hardRules = new HardRuleEngine(rules);
        RulesOnlyBaseline baseline = new RulesOnlyBaseline(hardRules);
        StateRenderer renderer = new StateRenderer(new ExternalStringSanitizer());
        GateLogic gate = new GateLogic(rules);
        Map<String, Object> questions = QuestionSchema.build(rules);

        String apiKey = loadApiKey();
        JevClient jev = apiKey.isBlank()
                ? new MockJevClient()
                : new TypeSafeJevClient(BASE_URL, apiKey, envOrDotEnv("JEV_MODEL", "jev-latest"), TIMEOUT_MS);

        // 2. mock 防护（审计 B4）
        if (jev.isMock() && !allowMock) {
            System.err.println("EVAL REFUSED: running in mock mode would produce a fake calibration report.");
            System.err.println("Set TYPESAFE_API_KEY (env or .env) for a real run, or pass --allow-mock to force a watermarked MOCK run.");
            System.exit(2);
        }
        boolean mockRun = jev.isMock();

        // 3. 样本（缺省生成；--regenerate 强制重建）
        List<LabeledSample> samples;
        if (regenerate || !Files.exists(SAMPLES)) {
            samples = new SampleGenerator().generate();
            writeSamples(samples, SAMPLES);
            System.out.println("generated " + samples.size() + " samples -> " + SAMPLES);
        } else {
            samples = readSamples(SAMPLES);
            System.out.println("loaded " + samples.size() + " samples from " + SAMPLES);
        }
        if (samples.size() != 100) {
            System.err.println("EVAL REFUSED: sample count must be exactly 100 (PRD quota), got " + samples.size());
            System.exit(2);
        }

        // 4. 逐样本跑三列
        List<EvalReport.Row> rows = new ArrayList<>();
        int degraded = 0;
        long totalInputTokens = 0;
        for (int i = 0; i < samples.size(); i++) {
            LabeledSample s = samples.get(i);
            for (TransferEvent setup : s.setupEvents()) featureBuilder.build(setup);

            Features f = featureBuilder.build(s.event());
            Action rulesAction = baseline.decide(s.event(), f);

            JevAnswers answers = null;
            try {
                answers = jev.evaluate(renderer.render(s.event(), f), questions);
                totalInputTokens += answers.inputTokens();
            } catch (TypeSafeJevClient.JevUnavailableException ex) {
                degraded++;
            }

            Action jevAction;
            Action combined;
            String route = null;
            Double riskConf = null;
            if (answers == null) {
                jevAction = Action.MANUAL_REVIEW;   // 降级口径与线上一致（PRD §5.2）
                combined = Action.MANUAL_REVIEW;
            } else {
                jevAction = gate.route(s.event().direction(), answers).action();
                HardRuleEngine.HardRuleHit hit = hardRules.check(s.event());
                combined = hit != null ? hit.action() : jevAction;
                route = answers.route();
                riskConf = answers.riskLevelConfidence();
            }
            rows.add(new EvalReport.Row(s, rulesAction, jevAction, combined, route, riskConf, answers));
            if ((i + 1) % 10 == 0) System.out.println("progress: " + (i + 1) + "/100");
        }

        // 5. 汇总 + 阈值扫描（离线复算门控，不额外消耗 API）
        EvalReport report = EvalReport.build(rows, rules, degraded, totalInputTokens, mockRun);

        // 6. 写报告
        Path out = mockRun ? REPORT.resolveSibling("MOCK-calibration-report.md") : REPORT;
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toMarkdown());
        System.out.println("\n=== summary ===");
        System.out.println(report.consoleSummary());
        System.out.println("report -> " + out);
    }

    private static boolean hasArg(String[] args, String flag) {
        for (String a : args) if (a.equals(flag)) return true;
        return false;
    }

    static RiskRulesConfig loadRules() throws IOException {
        try (InputStream in = EvalRunner.class.getResourceAsStream("/risk-rules.yml")) {
            if (in == null) throw new IOException("risk-rules.yml not on classpath");
            Map<String, Object> root = new Yaml().load(in);
            ObjectMapper om = new ObjectMapper();
            // Jackson 把 setPFreeze 识别为 "pfreeze"，与 YAML 的 camelCase 键差大小写；
            // Spring 的宽松绑定不存在于此处，需要显式放开（M4 实测）。
            om.configure(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
            @SuppressWarnings("unchecked")
            Map<String, Object> jevguard = (Map<String, Object>) root.get("jevguard");
            return om.convertValue(jevguard.get("rules"), RiskRulesConfig.class);
        }
    }

    private static String loadApiKey() {
        return envOrDotEnv("TYPESAFE_API_KEY", "");
    }

    private static String envOrDotEnv(String key, String dflt) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        Path dotenv = Path.of(".env");
        if (Files.exists(dotenv)) {
            try {
                for (String line : Files.readAllLines(dotenv)) {
                    line = line.trim();
                    if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
                }
            } catch (IOException ignored) {}
        }
        return dflt;
    }

    static void writeSamples(List<LabeledSample> samples, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        ObjectMapper om = new ObjectMapper();
        StringBuilder sb = new StringBuilder();
        for (LabeledSample s : samples) sb.append(om.writeValueAsString(s)).append('\n');
        Files.writeString(path, sb.toString());
    }

    static List<LabeledSample> readSamples(Path path) throws IOException {
        ObjectMapper om = new ObjectMapper();
        List<LabeledSample> out = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) out.add(om.readValue(line, LabeledSample.class));
        }
        return out;
    }
}
