# jev-guard PRD — Web3 充提实时风控网关

> v1 · 2026-09-22 · 依据 `docs/01-research.md`

## 1. 产品定位

一句话：**给交易所/钱包的充提链路加一道毫秒级 AI 分诊层**——一笔充值或提现进入系统后，jev-guard 在 100ms 内给出「放行 / 人工复核 / 冻结」的路由建议，附校准概率与完整决策日志。

不是什么：不是链上分析公司（不做地址画像数据库）、不是唯一裁判（硬规则优先）、不是交易 bot。

## 2. 目标用户与场景

- 开源受众：Web3 后端/风控工程师、交易所技术团队、AI 工程社区（Jev 生态参与者）
- 演示场景：模拟 EVM 充提事件流 → 特征工程 → Jev 分诊 → 门控路由 → 决策日志与校准报告

## 3. 核心流程

```
充提事件(模拟/真实) → FeatureBuilder(代码算特征) → 硬规则层(黑名单一票否决)
   → JevClient(一次请求4个原子问题) → GateLogic(confidence+分数阈值路由)
   → DecisionLog(JSONL落盘) → 离线校准评估(ECE/准确率/阈值扫描)
```

## 4. 功能需求

### 4.1 事件接入（P1 模拟，P3 可插拔真实源）
- 内置事件生成器：按可配置比例模拟正常充值、正常提现、黑名单地址、拆分(structuring)、快进快出、新地址突发、粉尘试探等模式
- 事件字段：chain, txHash, direction(deposit/withdraw), from, to, token, amountUsd, timestamp

### 4.2 特征工程（代码侧，不用 Jev 算数）
- 金额分档（dust / small / medium / large / whale，阈值可配）
- 账户侧：地址年龄、24h 充提次数、24h 累计金额、充提时间间隔
- 对手方：黑名单命中、标签（mixer/exchange/unknown）、首次交互
- 行为：近 1h 同额多笔、夜间操作、IP/设备占位字段
- 存储：默认 Caffeine 内存；`profile=redis` 可切 Redis（对齐简历技术栈叙事）

### 4.3 硬规则层
- 黑名单地址 → 直接 freeze（不调 Jev，省钱且权威）
- 单笔超限额 → 直接 manual_review
- 规则可配置（YAML），预留 Drools 式热更新接口但不一期实现

### 4.4 Jev 分诊层（一次请求，4 个原子问题）
| key | 类型 | 内容 |
|---|---|---|
| `risk_level` | score（5 档） | 综合风险等级：正常/关注/可疑/高风险/极高 |
| `pattern` | choice | normal / structuring / rapid_in_out / mixer_linked / new_address_burst / dust_probe |
| `should_freeze` | noul | 是否建议立即冻结该笔 |
| `route` | choice | auto_pass / manual_review / freeze |

- state = 文本化特征摘要（非原始 JSON），英文撰写（CJK 精度有损）
- model 默认 `jev-latest`，配置可锁版本

### 4.5 门控逻辑（Java 代码）
```
if hard_rule_hit: return hard_rule_action
if confidence < threshold_low: return MANUAL_REVIEW        # 不确定就升级
if should_freeze >= 0.8 and risk_level >= 4: return FREEZE
if route == freeze and confidence >= threshold_high: return FREEZE
if route == manual_review: return MANUAL_REVIEW
return AUTO_PASS
```
阈值全部走配置，由校准评估产出推荐值。

### 4.6 决策日志与观测
- 每笔落 JSONL：事件、特征、Jev 原始 answers、confidence、最终 action、耗时
- 暴露 REST：`POST /v1/screen`（单笔）、`GET /v1/decisions`（翻日志）、`GET /v1/metrics`（总量/分布/延迟）

### 4.7 校准评估（差异化卖点）
- 内置 ≥200 条人工标注样本（覆盖全部 pattern 类型）
- 输出：分 pattern 准确率、ECE 校准曲线数据、confidence 阈值扫描表（不同阈值下的误拦/漏放）
- 一键生成 `docs/calibration-report.md`

### 4.8 Mock 模式
- 无 `TYPESAFE_API_KEY` 时启用 MockJevClient，按事件类型返回确定性 fixture 答案，clone 即可跑通全流程

## 5. 非功能需求

- 单笔端到端 P95 < 300ms（Jev 官方 70–500ms，取其中位预期）
- 失败降级：Jev 超时/报错 → 该笔落 MANUAL_REVIEW（宁可人审，不可漏放），重试 1 次带退避
- 成本：日 10 万笔 ≈ $2.1，README 公示测算
- 密钥：仅 `.env` / 环境变量，`.gitignore` 排除，README 不写真实 key

## 6. 不做清单（一期）

- 真实链上节点接入（留接口，demo 用模拟流）
- 地址画像数据库 / 制裁名单实时源（用内置样例名单）
- Web 管理后台（用 REST + JSONL 替代）
- Drools 规则引擎（一期用 Java 策略类，README 说明演进路径）

## 7. 成功指标

- GitHub：发布后 2 周进 awesome-jev / awesome-jev-zh 收录；50+ star
- 技术：校准报告可复现；Mock 模式 5 分钟跑通
- 求职：面试可演示「充提事件 → 分诊 → 门控 → 校准报告」完整链路，与简历风控叙事互证
