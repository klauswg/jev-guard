# jev-guard 开发计划

> v1 · 2026-09-22 · 依据 `docs/01-research.md`、`docs/02-prd.md`

## 1. 技术选型（已定）

| 项 | 选择 | 理由 |
|---|---|---|
| 语言/框架 | **Java 17 + Spring Boot 3** | 与简历技术栈叙事一致，面试可讲；Jev 官方无 Java SDK，但 HTTP 形状简单，`RestClient` 直调即可 |
| Jev 调用 | 原生 HTTP `POST /v1/systemone`，自写 `JevClient` 封装（真实/Mock 双实现） | 避免引入 Python/JS SDK 混栈 |
| 特征存储 | Caffeine（默认内存）+ Redis profile（可选） | demo 零依赖起跑，简历关键词不丢 |
| 配置 | application.yml + risk-rules.yml | 阈值、档位、问题 schema 全部外置 |
| 日志 | JSONL 文件 + Micrometer 指标 | 免数据库 |
| 构建 | Maven，单模块 | 降低 clone 门槛 |
| License | Apache-2.0 | 生态主流（kev/laya 同） |

## 2. 里程碑

### P1 · 骨架与真机冒烟（0.5 天）
- [ ] Maven 工程骨架、`RestClient` 版 `JevClient`（含超时/重试/退避）
- [ ] `MockJevClient`（无 key 自动切换）
- [ ] 用真实 key 发一次最小请求（1 个 noul 问题）打通网络与鉴权
- [ ] `.env` / `application.yml` 密钥装配，`.gitignore` 就绪
- **验收**：`./mvnw spring-boot:run` 启动后 `POST /v1/screen` 返回一笔真实 Jev 答案

### P2 · 领域模型与核心链路（1 天）
- [ ] 事件模型、FeatureBuilder（金额分档/频次/速度/地址年龄/对手方标签）
- [ ] 硬规则层（黑名单一票否决、限额）
- [ ] 4 个原子问题的 schema（risk-rules.yml 外置）+ state 文本渲染器（英文）
- [ ] GateLogic 阈值路由 + DecisionLog（JSONL）
- **验收**：模拟器灌 100 笔混合事件，日志里每笔有 features/answers/action/latency

### P3 · REST 与可演示性（0.5 天）
- [ ] `POST /v1/screen`、`GET /v1/decisions`、`GET /v1/metrics`
- [ ] 事件生成器可配置比例（正常 70% / 各类风险 30%）
- [ ] 一条 `curl` 演示脚本（demo.sh / demo.ps1）
- **验收**：按 README 三步能看到连续事件流被实时路由

### P4 · 校准评估（1 天，差异化卖点）
- [ ] ≥200 条标注样本（6 类 pattern 均衡 + 边界 case）
- [ ] 评估器：分 pattern 准确率、ECE 分桶、阈值扫描表
- [ ] 生成 `docs/calibration-report.md`，推荐阈值回写 risk-rules.yml
- **验收**：报告可一键复现，阈值有数据依据

### P5 · 发布（0.5 天）
- [ ] README 中英双语（架构图、quickstart、校准结果、成本测算、与简历叙事呼应的设计取舍）
- [ ] push 到 github.com/klauswg/jev-guard，打 v0.1.0 tag
- [ ] 向 awesome-jev / awesome-jev-zh 提收录 PR
- [ ] 小红书笔记草稿（标题方向：「我用 Jev 给交易所充提做了个毫秒级风控网关」）

**合计约 3.5 天。**

## 3. 目录结构

```
jev-guard/
├── docs/                    # 01-research / 02-prd / 03-dev-plan / calibration-report
├── src/main/java/com/jevguard/
│   ├── JevGuardApplication.java
│   ├── client/              # JevClient 接口 + TypeSafeJevClient + MockJevClient
│   ├── domain/              # TransferEvent / Features / Decision / Action
│   ├── feature/             # FeatureBuilder + 特征窗口存储(Caffeine/Redis)
│   ├── rule/                # HardRuleEngine + risk-rules.yml 加载
│   ├── gate/                # GateLogic 阈值路由
│   ├── api/                 # REST controller
│   └── simulator/           # 事件流生成器
├── src/main/resources/
│   ├── application.yml
│   └── risk-rules.yml       # 金额档位/阈值/Jev 问题 schema
├── src/test/                # GateLogic 单测 + 评估器
├── eval/                    # 标注样本 labeled-samples.jsonl + EvalRunner
├── .env.example
├── .gitignore
└── README.md / README.zh-CN.md
```

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| Jev 在风控窄场景不如规则（调研已证窄任务基线可能更强） | 架构上 Jev 只做分诊层；校准报告如实呈现，诚实反而是 README 亮点 |
| early access 限速变动 | 重试+退避；mock 模式兜底演示 |
| 标注样本自己标、样本量小 | 报告里写明 n 与标注者，社区惯例如此（awesome-jev-zh 亦同） |
| key 泄露 | `.env` 不进 git；README 只放 `.env.example` |
