# jev-guard 校准报告

> 本报告基于**合成事件流**与**作者自行标注**的标签，n=100。它衡量的是模型对既定模式的识别一致性，**不是真实洗钱检出率**。生成器与标签同源，故本报告不构成对真实场景效果的证据。

- 生成时间：2026-09-22T17:20:22.014852800Z
- 样本：n=100（72 pattern / 18 boundary / 10 injection，与 PRD §4.7.1 配额一致）
- 降级样本（上游不可用→MANUAL_REVIEW）：0
- Jev input tokens 合计：75268

## 标注口径（作者标注，生成器与标签同源）

| pattern | 基准动作 |
|---|---|
| normal | AUTO_PASS |
| dust_probe / new_address_burst | MANUAL_REVIEW |
| structuring / rapid_in_out | 充值 MANUAL_REVIEW，提现 FREEZE |
| mixer_linked | FREEZE |

边界与注入 case 按同一口径标注，每条样本的 `note` 字段写明判断依据（见 `eval/labeled-samples.jsonl`）。

## 三列并列对照（审计 D10）

| 列 | 准确率 | 漏放(FREEZE→AUTO_PASS) | 误拦(AUTO_PASS→FREEZE) | 动作分布 |
|---|---|---|---|---|
| RulesOnlyBaseline（纯规则） | 68.0% | 0 | 2 | AUTO_PASS=41, MANUAL_REVIEW=21, FREEZE=38 |
| Jev 分诊门控 | 50.0% | 0 | 0 | AUTO_PASS=19, MANUAL_REVIEW=65, FREEZE=16 |
| 规则 + Jev 组合（完整链路） | 50.0% | 0 | 0 | AUTO_PASS=19, MANUAL_REVIEW=65, FREEZE=16 |

解读口径：三列同批样本同入口；规则列的打分权重与 Jev 列的阈值都是**拍定的初始值**，均未用本评估集调参。

## `route` 一致率（对照项，PRD §4.4）

Jev 直接给的 `route` 与代码组合门控动作的一致率：**72/100 = 72.0%**。

`route` 只记录不参与路由；这一列量化的是「Jev 直接路由」与「代码组合路由」的差异，差异本身即代码组合的价值。

## 信度分桶与 ECE（组合列，按 confidence[risk_level] 分桶）

| 桶 | n | 平均信度 | 桶内准确率 | 备注 |
|---|---|---|---|---|
| [0, 0.5) | 7 | 0.466 | 42.9% | 样本不足，不解读 |
| [0.5, 0.7) | 62 | 0.597 | 54.8% |  |
| [0.7, 0.8) | 11 | 0.737 | 72.7% |  |
| [0.8, 0.9) | 16 | 0.826 | 25.0% |  |
| [0.9, 1.0] | 4 | 0.923 | 25.0% | 样本不足，不解读 |

加权 ECE = **0.153**（n=100）。不画平滑校准曲线：n=100 下曲线抖动大于它要测的偏差（PRD §4.7.3）。

## 阈值扫描（仅 pFreeze，离线复算门控，不额外调 API）

| pFreeze | 准确率 | 漏放 | 误拦 |
|---|---|---|---|
| 0.60 | 50.0% | 0 | 0 |
| 0.70 | 50.0% | 0 | 0 |
| 0.80 | 50.0% | 0 | 0 |
| 0.90 | 50.0% | 0 | 0 |

当前生产值 pFreeze=0.80（cHigh=0.70, cLow=0.60, highRiskOrdinal=4）。推荐阈值需**人工确认后手动改 `risk-rules.yml`**，不自动回灌（PRD §4.7.3）。

## 复现

```bash
export TYPESAFE_API_KEY=...
mvn compile exec:java -Dexec.mainClass=com.jevguard.eval.EvalRunner
# 重建样本集：加 --regenerate；mock 强制跑（产物打水印）：--allow-mock
```
