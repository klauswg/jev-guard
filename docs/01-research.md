# jev-guard 调研报告

> 2026-09-22 · 调研来源：TypeSafe 官方文档/发布博文、awesome-jev-zh 中文精选列表（含多处作者实测）、Jev Patterns、多篇独立评测。

## 1. Jev 是什么（一句话）

TypeSafe 的 System One 决策模型：输入一份 `state` + 一组带类型的问题，**一次前向**返回校准过的概率/选择/分数，70–500ms，不生成任何文本。本质是「一次前沿智能的函数调用」。

## 2. API 硬规格（已核实 2026-09-21/22 多源一致）

| 项 | 值 |
|---|---|
| 端点 | `POST https://api.typesafe.ai/v1/systemone`（另有 `GET /v1/models`） |
| 认证 | `Authorization: Bearer <key>` |
| 模型 | `jev-latest`（当前 = `jev-1.13.0`），生产建议锁定版本号 |
| 请求体 | `{model, state, questions}`；state 可为 string/object/array，仅文本 |
| 上下文 | 单请求 64k token；state + 最长问题 ≤ 32k |
| 三种原语 | `choice`（criteria 为对象：选项→描述，≤255 项）/ `score`（criteria 为 2-10 档有序数组）/ `noul`（是非，返回 0-1 概率） |
| 返回 | `answers.{name}` 含结果值 + `confidence` + 各选项 `probabilities`；`usage.input_tokens`（输出免费） |
| 价格 | **$0.042 / 百万输入 token**，输出不计费 |
| 限速 | 250k token/秒，1200 请求/分钟（early access 期间可能调整） |
| 同请求多问题 | 并行求值、彼此隔离（互相看不见对方答案），实测加问题不增延迟 |

## 3. 语义关键：confidence 与校准

- `choice/score` 回答「是什么」，`confidence` 回答「要不要动手」——**门控必须同时看两个字段**。
- confidence 是分布的尖锐度，不是「这条一定对」；校准是群体统计性质，**阈值必须在自己的标注数据上重新量**（官方反复声明）。
- 中文实测（n=15 社区样本）：准确率没明显掉链子，但 confidence 偏饱和（大量 1.00），阈值别卡太高；**攒批发送可能比逐条更准**（同批样本互为参照）。

## 4. 能力毛边（官方公布的失败模式 → 我们的设计约束）

| 毛边 | 对 jev-guard 的约束 |
|---|---|
| 不擅长数字/计数/日期比较 | **所有算术在代码里做**：金额分档、频次、速度等特征先算好再喂文本化 |
| 长而嘈杂的 state 准确率下降 | state 只放结构化特征摘要，不塞原始 tx 全量 JSON |
| state 内注入指令可干扰结果 | 地址/备注等外部内容做转义与截断 |
| 字面理解，边界情况不进 criteria 就会错分 | 风控问题的 criteria 必须写清边界（金额档位、时间窗定义） |
| 窄任务上传统基线可能更准（独立评测：钓鱼邮件 Jev 62.6% vs 正则 91.8%） | **Jev 不做唯一裁判**：硬规则（黑名单）一票否决，Jev 做快速分诊层 |

## 5. 竞品/空位扫描（发布第 7 天，全生态 1800+ 项目）

已拥挤：浏览器操作（jev-ultrafast 14k★）、上下文压缩（fast-jev-compaction）、交易 bot（jev-trader / QuantDinger）、开源复刻（kev/SemIf/laya/NanoJev）、awesome 清单（多份）。
**空白：Web3 风控/合规矩阵方向 0 个项目。** 充提风控、地址风险分诊、反洗钱模式识别无人做——这是 jev-guard 的卡位依据。

## 6. 成本测算

单次充提风控请求：state（结构化特征 ≈ 300 token）+ 4 个问题（≈ 200 token）≈ 500 输入 token ≈ **$0.000021/笔**。日 10 万笔 ≈ $2.1/天。成本不是约束。

## 7. 关键设计结论（输入给 PRD）

1. **Jev 是「分诊层」不是「裁判」**：黑名单/制裁名单走硬规则一票否决；Jev 输出 risk_level/pattern/route 建议 + confidence，门控逻辑在 Java 代码里。
2. **问题原子化**：一次请求 4 个原子问题（score + choice + noul + choice），组合逻辑自己写（官方心法，实测值 32 个百分点）。
3. **特征代码化**：金额分档、24h 频次、速度、地址年龄全部 Java 侧预计算，文本化进 state。
4. **评测即卖点**：内置 labeled 样本集 + 校准报告（ECE/准确率/阈值扫描）——官方和社区都缺垂直场景校准数据，这是我们 README 的差异化弹药。
5. **无 key 可跑**：Mock 模式返回 fixture 答案，clone 即跑；有 key 切真 API。SDK 只有 Python/JS，但 HTTP 形状简单，Java RestClient 直接调。
