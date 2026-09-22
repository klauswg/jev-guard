package com.jevguard.eval;

import com.jevguard.domain.Action;
import com.jevguard.domain.JevAnswers;
import com.jevguard.gate.GateLogic;
import com.jevguard.rule.RiskRulesConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 评估汇总与报告渲染（PRD §4.7.2 / §4.7.3）。
 * 三列并列 + route 一致率 + 信度分桶（≤5 桶、每桶印 n、n<10 不解读）+ 加权 ECE。
 * 阈值扫描：先查信度是否饱和（>=80% 样本 conf>=0.9）；饱和则退化为
 * "信度分布刻画"，不硬做一张没有区分度的空表（01-research §3）。
 */
public class EvalReport {

    public record Row(LabeledSample sample, Action rulesAction, Action jevAction,
                      Action combined, String route, Double riskConf, JevAnswers answers) {}

    record ColumnStats(int correct, int total, EnumMap<Action, Integer> dist,
                       int missedFreeze, int falseFreeze) {
        double accuracy() { return total == 0 ? 0 : (double) correct / total; }
    }

    private final List<Row> rows;
    private final RiskRulesConfig rules;
    private final int degraded;
    private final long totalInputTokens;
    private final boolean mockRun;

    private ColumnStats colRules, colJev, colCombined;
    private int routeAgree, routeTotal;
    private double[][] buckets; // [bucket]{n, confSum, correct}
    private double ece;
    private boolean saturated;
    private List<double[]> sweep; // {pFreeze, accuracy, missedFreeze, falseFreeze}

    private EvalReport(List<Row> rows, RiskRulesConfig rules, int degraded, long totalInputTokens, boolean mockRun) {
        this.rows = rows;
        this.rules = rules;
        this.degraded = degraded;
        this.totalInputTokens = totalInputTokens;
        this.mockRun = mockRun;
    }

    public static EvalReport build(List<Row> rows, RiskRulesConfig rules, int degraded,
                                   long totalInputTokens, boolean mockRun) {
        EvalReport r = new EvalReport(rows, rules, degraded, totalInputTokens, mockRun);
        r.compute();
        return r;
    }

    private void compute() {
        colRules = stats(1);
        colJev = stats(2);
        colCombined = stats(3);

        routeAgree = 0;
        routeTotal = 0;
        for (Row row : rows) {
            Action ra = mapRoute(row.route());
            if (ra != null) {
                routeTotal++;
                if (ra == row.combined()) routeAgree++;
            }
        }

        // 信度分桶：[0,0.5) [0.5,0.7) [0.7,0.8) [0.8,0.9) [0.9,1.0]
        buckets = new double[5][3];
        for (Row row : rows) {
            if (row.riskConf() == null) continue;
            double c = row.riskConf();
            int b = c < 0.5 ? 0 : c < 0.7 ? 1 : c < 0.8 ? 2 : c < 0.9 ? 3 : 4;
            buckets[b][0]++;
            buckets[b][1] += c;
            if (row.combined() == row.sample().expectedAction()) buckets[b][2]++;
        }
        double n = 0, sum = 0;
        for (double[] b : buckets) {
            if (b[0] == 0) continue;
            n += b[0];
            sum += b[0] * Math.abs(b[2] / b[0] - b[1] / b[0]);
        }
        ece = n == 0 ? 0 : sum / n;

        // 饱和探针
        long hi = rows.stream().filter(r -> r.riskConf() != null && r.riskConf() >= 0.9).count();
        long withConf = rows.stream().filter(r -> r.riskConf() != null).count();
        saturated = withConf > 0 && (double) hi / withConf >= 0.8;

        if (!saturated) {
            sweep = new ArrayList<>();
            for (double p : new double[]{0.60, 0.70, 0.80, 0.90}) {
                sweep.add(sweepAt(p));
            }
        }
    }

    /** 离线复算门控（不额外调 API）：仅扫 pFreeze，cHigh/cLow/highRiskOrdinal 不动。 */
    private double[] sweepAt(double pFreeze) {
        RiskRulesConfig copy = new RiskRulesConfig();
        copy.setAmountBands(rules.getAmountBands());
        copy.setHardRules(rules.getHardRules());
        copy.setQuestions(rules.getQuestions());
        RiskRulesConfig.Gate g = new RiskRulesConfig.Gate();
        g.setPFreeze(pFreeze);
        g.setCHigh(rules.getGate().getCHigh());
        g.setCLow(rules.getGate().getCLow());
        g.setHighRiskOrdinal(rules.getGate().getHighRiskOrdinal());
        copy.setGate(g);
        GateLogic gate = new GateLogic(copy);

        int correct = 0, missed = 0, falseF = 0, total = 0;
        for (Row row : rows) {
            if (row.answers() == null) continue; // 降级样本不参与扫描
            Action a = gate.route(row.sample().event().direction(), row.answers()).action();
            Action exp = row.sample().expectedAction();
            total++;
            if (a == exp) correct++;
            if (exp == Action.FREEZE && a == Action.AUTO_PASS) missed++;
            if (exp == Action.AUTO_PASS && a == Action.FREEZE) falseF++;
        }
        return new double[]{pFreeze, total == 0 ? 0 : (double) correct / total, missed, falseF};
    }

    private ColumnStats stats(int col) {
        EnumMap<Action, Integer> dist = new EnumMap<>(Action.class);
        int correct = 0, missed = 0, falseF = 0;
        for (Row row : rows) {
            Action a = switch (col) {
                case 1 -> row.rulesAction();
                case 2 -> row.jevAction();
                default -> row.combined();
            };
            Action exp = row.sample().expectedAction();
            dist.merge(a, 1, Integer::sum);
            if (a == exp) correct++;
            if (exp == Action.FREEZE && a == Action.AUTO_PASS) missed++;
            if (exp == Action.AUTO_PASS && a == Action.FREEZE) falseF++;
        }
        return new ColumnStats(correct, rows.size(), dist, missed, falseF);
    }

    private static Action mapRoute(String route) {
        if (route == null) return null;
        return switch (route) {
            case "auto_pass" -> Action.AUTO_PASS;
            case "manual_review" -> Action.MANUAL_REVIEW;
            case "freeze" -> Action.FREEZE;
            default -> null;
        };
    }

    public String consoleSummary() {
        return """
                rules-only  acc=%.1f%%  漏放=%d 误拦=%d
                jev-gate    acc=%.1f%%  漏放=%d 误拦=%d
                combined    acc=%.1f%%  漏放=%d 误拦=%d
                route 一致率=%.1f%% (n=%d)  ECE=%.3f  信度饱和=%s  degraded=%d  inputTokens=%d""".formatted(
                colRules.accuracy() * 100, colRules.missedFreeze(), colRules.falseFreeze(),
                colJev.accuracy() * 100, colJev.missedFreeze(), colJev.falseFreeze(),
                colCombined.accuracy() * 100, colCombined.missedFreeze(), colCombined.falseFreeze(),
                routeTotal == 0 ? 0 : routeAgree * 100.0 / routeTotal, routeTotal,
                ece, saturated ? "是" : "否", degraded, totalInputTokens);
    }

    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        if (mockRun) {
            md.append("# MOCK RUN — NOT A REAL CALIBRATION\n\n");
            md.append("> ⚠️ 本报告由 MockJevClient（确定性 fixture）产出，仅用于流程验证，**不构成任何校准证据**。\n\n");
        }
        md.append("# jev-guard 校准报告\n\n");
        md.append("> 本报告基于**合成事件流**与**作者自行标注**的标签，n=100。它衡量的是模型对既定模式的识别一致性，**不是真实洗钱检出率**。生成器与标签同源，故本报告不构成对真实场景效果的证据。\n\n");
        md.append("- 生成时间：").append(Instant.now()).append("\n");
        md.append("- 样本：n=").append(rows.size()).append("（72 pattern / 18 boundary / 10 injection，与 PRD §4.7.1 配额一致）\n");
        md.append("- 降级样本（上游不可用→MANUAL_REVIEW）：").append(degraded).append("\n");
        md.append("- Jev input tokens 合计：").append(totalInputTokens).append("\n\n");

        md.append("## 标注口径（作者标注，生成器与标签同源）\n\n");
        md.append("| pattern | 基准动作 |\n|---|---|\n");
        md.append("| normal | AUTO_PASS |\n");
        md.append("| dust_probe / new_address_burst | MANUAL_REVIEW |\n");
        md.append("| structuring / rapid_in_out | 充值 MANUAL_REVIEW，提现 FREEZE |\n");
        md.append("| mixer_linked | FREEZE |\n\n");
        md.append("边界与注入 case 按同一口径标注，每条样本的 `note` 字段写明判断依据（见 `eval/labeled-samples.jsonl`）。\n\n");

        md.append("## 三列并列对照（审计 D10）\n\n");
        md.append("| 列 | 准确率 | 漏放(FREEZE→AUTO_PASS) | 误拦(AUTO_PASS→FREEZE) | 动作分布 |\n");
        md.append("|---|---|---|---|---|\n");
        row(md, "RulesOnlyBaseline（纯规则）", colRules);
        row(md, "Jev 分诊门控", colJev);
        row(md, "规则 + Jev 组合（完整链路）", colCombined);
        md.append("\n解读口径：三列同批样本同入口；规则列的打分权重与 Jev 列的阈值都是**拍定的初始值**，均未用本评估集调参。\n\n");

        md.append("## `route` 一致率（对照项，PRD §4.4）\n\n");
        md.append("Jev 直接给的 `route` 与代码组合门控动作的一致率：**%d/%d = %.1f%%**。\n\n"
                .formatted(routeAgree, routeTotal, routeTotal == 0 ? 0 : routeAgree * 100.0 / routeTotal));
        md.append("`route` 只记录不参与路由；这一列量化的是「Jev 直接路由」与「代码组合路由」的差异，差异本身即代码组合的价值。\n\n");

        md.append("## 信度分桶与 ECE（组合列，按 confidence[risk_level] 分桶）\n\n");
        md.append("| 桶 | n | 平均信度 | 桶内准确率 | 备注 |\n|---|---|---|---|---|\n");
        String[] labels = {"[0, 0.5)", "[0.5, 0.7)", "[0.7, 0.8)", "[0.8, 0.9)", "[0.9, 1.0]"};
        for (int i = 0; i < 5; i++) {
            double[] b = buckets[i];
            if (b[0] == 0) {
                md.append("| ").append(labels[i]).append(" | 0 | - | - | 空桶 |\n");
            } else {
                String note = b[0] < 10 ? "样本不足，不解读" : "";
                md.append("| %s | %d | %.3f | %.1f%% | %s |\n".formatted(
                        labels[i], (int) b[0], b[1] / b[0], b[2] / b[0] * 100, note));
            }
        }
        md.append("\n加权 ECE = **%.3f**（n=%d）。不画平滑校准曲线：n=100 下曲线抖动大于它要测的偏差（PRD §4.7.3）。\n\n"
                .formatted(ece, rows.stream().filter(r -> r.riskConf() != null).count()));

        if (saturated) {
            md.append("## 信度分布刻画（饱和，阈值扫描退化）\n\n");
            md.append("confidence[risk_level] ≥ 0.9 的样本占比 ≥ 80%，**信度已饱和**（01-research §3 的传闻在本数据集上成立）。");
            md.append("此时阈值扫描表没有区分度，按 PRD §4.7.3 退化为分布刻画：\n\n");
            long hi = rows.stream().filter(r -> r.riskConf() != null && r.riskConf() >= 0.9).count();
            long total = rows.stream().filter(r -> r.riskConf() != null).count();
            double min = rows.stream().filter(r -> r.riskConf() != null).mapToDouble(Row::riskConf).min().orElse(0);
            md.append("- conf ≥ 0.9：%d/%d（%.1f%%）\n".formatted(hi, total, total == 0 ? 0 : hi * 100.0 / total));
            md.append("- 最小值：%.3f\n".formatted(min));
            md.append("- 含义：confidence 在本场景不携带排序信息，门控应以 risk_level 序数 + should_freeze 概率为主，信度仅作低信度兜底（C_LOW）。\n\n");
        } else {
            md.append("## 阈值扫描（仅 pFreeze，离线复算门控，不额外调 API）\n\n");
            md.append("| pFreeze | 准确率 | 漏放 | 误拦 |\n|---|---|---|---|\n");
            for (double[] s : sweep) {
                md.append("| %.2f | %.1f%% | %d | %d |\n".formatted(s[0], s[1] * 100, (int) s[2], (int) s[3]));
            }
            md.append("\n当前生产值 pFreeze=%.2f（cHigh=%.2f, cLow=%.2f, highRiskOrdinal=%d）。推荐阈值需**人工确认后手动改 `risk-rules.yml`**，不自动回灌（PRD §4.7.3）。\n\n"
                    .formatted(rules.getGate().getPFreeze(), rules.getGate().getCHigh(),
                            rules.getGate().getCLow(), rules.getGate().getHighRiskOrdinal()));
        }

        md.append("## 复现\n\n");
        md.append("```bash\nexport TYPESAFE_API_KEY=...\nmvn compile exec:java -Dexec.mainClass=com.jevguard.eval.EvalRunner\n");
        md.append("# 重建样本集：加 --regenerate；mock 强制跑（产物打水印）：--allow-mock\n```\n");
        return md.toString();
    }

    private void row(StringBuilder md, String name, ColumnStats s) {
        md.append("| %s | %.1f%% | %d | %d | %s |\n".formatted(
                name, s.accuracy() * 100, s.missedFreeze(), s.falseFreeze(), distStr(s.dist())));
    }

    private static String distStr(EnumMap<Action, Integer> dist) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Action, Integer> e : dist.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
