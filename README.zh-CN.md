# jev-guard

**面向交易所充提场景的实时风控分诊网关——Jev（TypeSafe System One）只做分诊层，裁判权留在确定性代码里。**

[English README](README.md)

Jev 是一个对结构化 state 回答问题的决策模型。本项目用它回答一个问题：*给定一笔充值/提现及其 24 小时行为特征，该放行、人审还是冻结？* 风险等级、行为模式、冻结概率由 Jev 给出；**阈值、路由矩阵和最终动作全部留在 Java**——因为只有在那里，它们才能被单测、被审计、被调参。

## 为什么是这个架构

- **Jev 分诊，代码裁判。** 硬规则层（黑名单/制裁对手方/单笔限额）在调用模型之前一票否决，模型永远拿不到最终决定权。
- **高风险提现只冻结、不降级。** 提现一旦广播不可逆；高风险充值已经上链，转为账户复查。direction × action 语义矩阵编码了这种不对称。
- **`route` 只记录、不执行。** Jev 自己给的路由建议保留为对照项，用来量化「模型直接路由」与「代码组合决策」的分歧（校准集上一致率 72%——那 28% 的分歧正是组合逻辑留在代码里的理由）。
- **降级只到人审，绝不放行。** 上游超时/5xx/429 → `MANUAL_REVIEW`。宁可麻烦分析师，不可漏放一笔。

## 架构

```
充提事件
    │
    ▼
FeatureBuilder ── 24h 窗口（Caffeine 内存）：笔数/金额/同额连发/地址年龄/快进快出
    │
    ▼
HardRuleEngine ── 黑名单 / 制裁标签 / >$50万  ──命中──► FREEZE / MANUAL_REVIEW（不调模型）
    │ 未命中
    ▼
StateRenderer ── 英文 key=value state，空值不渲染，
    │            外部字符串白名单清洗（防 prompt 注入）
    ▼
Jev (System One) ── 4 问：risk_level(score) / pattern(choice) / should_freeze(noul) / route（仅对照）
    │                                  失败 → MANUAL_REVIEW（降级）
    ▼
GateLogic ── pFreeze / cHigh / cLow / highRiskOrdinal，按方向分叉
    ▼
Decision + JSONL 审计日志（地址脱敏）
```

## 快速开始（mock 模式，无需 key）

需要 JDK 17+ 和 Maven。

```bash
git clone https://github.com/klauswg/jev-guard && cd jev-guard
mvn spring-boot:run

# 第二个终端
./demo.sh        # Windows 用 .\demo.ps1
```

能看到 5 笔模拟充提事件被实时路由、延迟指标和脱敏审计日志。mock 模式返回确定性 fixture 答案，全链路离线可跑。

## 真实模式

```bash
cp .env.example .env   # 填入你的 TypeSafe API key
mvn spring-boot:run
```

应用启动时读取 `.env`。有 key 即走真实客户端（429 读 retry-after；超时/5xx 退避重试一次；其他 4xx 直接进降级）。

## 端点

| 端点 | 说明 |
|---|---|
| `POST /v1/screen` | 分诊单笔充提事件 |
| `POST /v1/simulate?count=N` | 跑 N 笔模拟事件 |
| `GET /v1/decisions?limit=N` | 最近决策（地址脱敏） |
| `GET /v1/metrics` | 动作计数、延迟分位（未重试/含重试分开统计） |

默认绑 `127.0.0.1:8080`——仅 demo 用途，见局限性声明。

## 校准结果（真实 API，n=100）

同一标注样本集跑三列（72 pattern / 18 边界 / 10 注入，合成事件、作者自标——免责声明见[完整报告](docs/calibration-report.md)）：

| 列 | 准确率 | 漏放 | 误拦 |
|---|---|---|---|
| 纯规则基线 | **68.0%** | 0 | 2 |
| Jev 分诊门控 | 50.0% | 0 | 0 |
| 规则 + Jev 组合 | 50.0% | 0 | 0 |

诚实版发现：

1. **这个窄场景下规则基线赢了 Jev。** 这不是尴尬，是架构的验证：模型是分诊层，从来不是裁判。Jev 过度升级（65/100 送人审）但**漏放为零**。
2. **confidence 在此场景不携带排序信息。** 它没有饱和（62% 落在 0.5–0.7），但最高信度桶的准确率反而最低（25%）。门控应以风险序数 + 冻结概率为主，信度只做低信度兜底。
3. **冻结阈值扫描是平的**（pFreeze 0.60–0.90 全是 50%）——准确率瓶颈在 risk_level 序数而非冻结阈值，因此我们一个阈值都没改。
4. **`route` 与组合动作一致率 72%。** 28% 的分歧就是「组合留在代码」的可量化价值。

复现：

```bash
export TYPESAFE_API_KEY=...
mvn compile exec:java -Dexec.mainClass=com.jevguard.eval.EvalRunner
# 重建样本：--regenerate；强制 mock（产物打水印）：--allow-mock
```

评估器在 mock 模式下**默认拒绝运行**——mock 出来的"校准"是假证据。强制 mock 的产物带 `MOCK-` 前缀和水印。

## 成本

真实 API 实测（2026-09-23，`jev-1.13.0`）：每次分诊约 395 input + 36 output tokens（4 个问题 + 约 20 行 state），100 笔合计 75,268 input tokens。稳态延迟 p50 ≈ 272ms、p95 ≈ 830ms（实测，`GET /v1/metrics` 打印实时值；厂商自报区间 70–500ms）。

## 设计取舍

- **算术在代码，判断在模型。** 窗口、档位、连发等全部算术是确定性 Java，模型只看到消化后的 state。
- **阈值外置** 到 `risk-rules.yml`；评估给出的推荐值人工确认后手动改，绝不自动回灌（用自己的评估集调参再"验证"自己是循环论证）。
- **防注入**：token 名/标签是攻击者可控字段 → 白名单清洗 + 评估集内 10 条注入回归样本。注入没有改变任何一条样本的路由结果。
- **noul 没有 confidence**（实测）：API 不返回，冻结信度由客户端派生 `|v − 0.5| × 2`。score 概率是 **0 基索引**（同为实测、文档未载）。见 [docs/05-m1-findings.md](docs/05-m1-findings.md)。

## 局限性声明（引用前请读）

1. 硬规则层**机制已实现、数据源未接入**——内置样例名单，非真实制裁名单。
2. 内存 profile 下**重启丢失 24h 特征窗口**；Redis profile 接口预留，未压测。
3. 校准样本为**合成事件 + 作者自标，n=100**——衡量模式识别一致性，**不是真实洗钱检出率**。
4. REST 端点 **demo-only、默认仅本机**，生产需前置鉴权。
5. 延迟区间 70–500ms 为**厂商自报**；我方数字为自测实测值，测法公开。
6. 不引用无法溯源的第三方评测数字。

## 文档

- [docs/01-research.md](docs/01-research.md) — 生态调研与空位分析
- [docs/02-prd.md](docs/02-prd.md) — 产品需求
- [docs/03-dev-plan.md](docs/03-dev-plan.md) — 里程碑 M1–M5
- [docs/04-audit.md](docs/04-audit.md) — 塑造 v2 的那份审计
- [docs/05-m1-findings.md](docs/05-m1-findings.md) — 实测 API 事实（0 基索引、noul 信度、criteria 要求）
- [docs/calibration-report.md](docs/calibration-report.md) — 完整校准报告

## License

MIT
