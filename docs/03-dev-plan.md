# jev-guard 开发计划

> v2 · 2026-09-23 · v1（2026-09-22）见 git 提交 `12a4bbe`
> 依据 `docs/01-research.md` v2、`docs/02-prd.md` v2、`docs/04-audit.md`

| 审计编号 | 问题 | v2 落点 |
|---|---|---|
| S1 | v1 的 P1 验收要 `/v1/screen`，但 REST 在 P3、特征在 P2 | §2 M1（= 原 P1）验收改为集成测试打通鉴权，端点只在 M3 定义一次 |
| S2 | PRD 与本文两套 P 编号并存 | 里程碑改 **M1–M5**；PRD 改"一期/二期"，编号不再撞 |
| S3 | 真实链上源口径矛盾 | 统一为只留 SPI，一期不接（PRD §6） |
| S4 | "合计 3.5 天"是理想工时不含返工 | §3 双档工期，且说明为何 v2 工作量反而上升 |
| S5 | 成功指标含控制不了的外部结果 | 指标统一到 PRD §7，本文只列动作 |
| S6 | awesome 清单实际有 7 份以上 | M5 改为投 star 最高的 2–3 份，收录降级为观察项 |
| S7 | Redis profile 可能是空承诺 | M2 设明确决策点：真跑一次，或 README 写"未压测" |

## 1. 技术选型（已定）

| 项 | 选择 | 理由 |
|---|---|---|
| 语言/框架 | **Java 17 + Spring Boot 3** | 与简历技术栈叙事一致，面试可讲；HTTP 形状简单，`RestClient` 直调即可 |
| Jev 调用 | 原生 HTTP `POST /v1/systemone`，自写 `JevClient`（真实/Mock 双实现） | 避免混栈。注：「官方只有 Python/JS SDK」这条**未核验**，但不影响结论——即便有 Java SDK，自写客户端也便于控制 429/retry-after 语义（PRD §5.2） |
| 特征存储 | Caffeine（默认内存）+ Redis profile（可选，见 M2 决策点） | demo 零依赖起跑 |
| 配置 | application.yml + risk-rules.yml | 阈值、档位、问题 schema、criteria 全部外置 |
| 日志 | JSONL 文件 + Micrometer 指标 | 免数据库 |
| 构建 | Maven，单模块 | 降低 clone 门槛 |
| License | Apache-2.0 | 生态主流 |

## 2. 里程碑

### M1 · 骨架与真机冒烟（理想 0.5 天 / 含返工 1 天）

- [ ] Maven 工程骨架、`RestClient` 版 `JevClient`
- [ ] 错误分流：**429 读 `retry-after` 与超时/5xx 退避重试分开实现**（PRD §5.2，不是笼统"重试一次"）
- [ ] `MockJevClient`（无 key 自动切换）
- [ ] 用真实 key 发一次最小请求（1 个 noul 问题）打通网络与鉴权
- [ ] **固化真实响应体字段名**，替掉 01-research §2 里标为部分核验的猜测（answers/confidence/probabilities/usage 的精确形状）
- [ ] **20 条探针测 confidence 是否饱和**（01-research §3 的未核验传闻，用自己的数据替掉它）
- [ ] **200 次真实调用测延迟分位数**，回填 PRD §5.1 表格
- [ ] `.env` / `application.yml` 密钥装配
- **验收**：一个集成测试（非 REST 端点）用真实 key 拿到一笔答案，并打印出实测 token 数、延迟分位数、信度分布三项

### M2 · 领域模型与核心链路（理想 1.5 天 / 含返工 2.5 天）

- [ ] 事件模型（含 `addressFirstSeenAt` 作为**输入字段**，PRD §4.1）
- [ ] FeatureBuilder（金额分档/频次/速度/地址年龄/对手方标签），空值不产出
- [ ] `StateRenderer` + **`ExternalStringSanitizer`（白名单+截断+剥控制字符，PRD §4.4a）**
- [ ] 硬规则层（黑名单一票否决、限额）
- [ ] 4 个问题的 schema（risk-rules.yml 外置，criteria 全英文，5 档序数映射）
- [ ] GateLogic：按 PRD §4.5.1 的顺序，含 direction 分叉
- [ ] DecisionLog（JSONL，含每问题 confidence、分段耗时、retry 标记）
- [ ] **Redis 决策点**：用 docker 起一个 Redis 真跑一遍并留日志证据；跑不动就在 README 写"接口预留，未压测"。两者选一，不允许留空承诺
- **验收**：模拟器灌 100 笔混合事件，日志里每笔有 features / 4 个 answers 及各自 confidence / action / 分段耗时；GateLogic 单测覆盖 PRD §4.5.1 的 6 个判定步骤、7 条返回路径 + 注入 case

### M3 · REST 与可演示性（理想 0.5 天 / 含返工 1 天）

- [ ] `POST /v1/screen`、`GET /v1/decisions`、`GET /v1/metrics`（**端点在此首次定义**，审计 S1）
- [ ] 默认绑 `127.0.0.1`；`/v1/decisions` 地址中段脱敏（PRD §4.6.2）
- [ ] `/v1/metrics` 延迟分位数分两组：含重试 / 不含重试
- [ ] 事件生成器可配置比例（正常 70% / 各类风险 30%）
- [ ] 一条 `curl` 演示脚本（demo.sh / demo.ps1）
- **验收**：按 README 三步能看到连续事件流被实时路由；metrics 里的延迟与 M1 实测一致

### M4 · 校准评估（理想 1 天 / 含返工 1.5 天，差异化卖点）

顺序按性价比排，**基线对照排第一**——它比 ECE 重要得多（审计 D10）。

- [ ] **`RulesOnlyBaseline`**：纯硬规则+阈值判别，与 Jev 链路共用同一份样本入口
- [ ] 100 条标注样本，按 PRD §4.7.1 的配额表（72 pattern + 18 边界 + 10 注入 = 100，审计 B3 把 200 砍到 100）
- [ ] 评估器输出**三列并列**：规则基线 / Jev 分诊 / 规则+Jev 组合，外加 `route` 一致率列
- [ ] 分桶 ≤5 桶且每桶印 n；n<10 标"样本不足，不解读"；不画平滑曲线
- [ ] 若 M1 探针确认 confidence 饱和 → 阈值扫描改为"信度分布刻画"（PRD §4.7.3）
- [ ] **EvalRunner 在 mock 模式硬失败**；`--allow-mock` 产物打 `MOCK-` 前缀与水印（审计 B4）
- [ ] 生成 `docs/calibration-report.md`，首页顶格写 PRD §4.7.1 的免责声明原文
- [ ] 推荐阈值**人工确认后手动**改 `risk-rules.yml`，不自动回灌
- **验收**：报告可一键复现；三列数字齐全；免责声明与每桶 n 都在

### M5 · 发布（理想 0.5 天 / 含返工 1 天）

- [ ] README 中英双语：架构图、quickstart、三列校准结果、成本测算、设计取舍
- [ ] **README 含 PRD §8 的 6 条局限性声明**（这是发布前的阻断项）
- [ ] 首次 `git add` 前再确认一次 `git status` 里没有 `.env`
- [ ] push 到 github.com/klauswg/jev-guard，打 v0.1.0 tag
- [ ] 向 **star 数最高的 2–3 份** awesome 清单提 PR（实际有 7 份以上同名列表，01-research §5；单份收录价值被稀释，别把它当目标）
- [ ] 小红书笔记草稿（方向：「我用 Jev 给交易所充提做了个 AI 分诊网关」——**标题不写"毫秒级"**，审计 B1）

## 3. 工期：双档，不给单一精确数

| 里程碑 | 理想 | 含返工 |
|---|---|---|
| M1 骨架与真机冒烟 | 0.5 | 1 |
| M2 领域模型与核心链路 | 1.5 | 2.5 |
| M3 REST 与可演示性 | 0.5 | 1 |
| M4 校准评估 | 1 | 1.5 |
| M5 发布 | 0.5 | 1 |
| **合计** | **4 天** | **7 天** |

v1 写"合计约 3.5 天"。v2 反而更长，两个原因都要说清：

1. **v1 的 P2（= 现 M2）是被压缩的。** 一天塞进事件模型 + 5 类特征 + 硬规则 + YAML schema + state 渲染 + GateLogic + JSONL 七个组件，v2 还加了清洗器和 direction 分叉。1.5 天是下限。
2. **v2 新增了 v1 没有的活**：注入清洗与回归 case、direction 语义分叉、规则基线、mock 防护、M1 的三项实测（token/延迟/信度饱和）。M4 的样本量虽从 200 砍到 100，省下的时间被基线对照吃掉了。

另外，全程在对接一个**上线仅一周、字段形状未完全核实、限速数值未核实**的外部 API，M1 的返工概率明显高于普通项目。按文档纪律：**对外只说"约一周"，不说 3.5 天。**

## 4. 目录结构

```
jev-guard/
├── docs/                    # 01-research / 02-prd / 03-dev-plan / 04-audit / calibration-report
├── src/main/java/com/jevguard/
│   ├── JevGuardApplication.java
│   ├── client/              # JevClient 接口 + TypeSafeJevClient + MockJevClient + 错误分流(429/超时)
│   ├── domain/              # TransferEvent / Features / Decision / Action / RiskLevel(序数映射)
│   ├── feature/             # FeatureBuilder + 特征窗口存储(Caffeine/Redis)
│   ├── state/               # StateRenderer + ExternalStringSanitizer   ← v2 新增
│   ├── rule/                # HardRuleEngine + risk-rules.yml 加载
│   ├── gate/                # GateLogic 阈值路由(含 direction 分叉)
│   ├── api/                 # REST controller（默认绑 127.0.0.1）
│   └── simulator/           # 事件流生成器
├── src/main/resources/
│   ├── application.yml
│   └── risk-rules.yml       # 金额档位/阈值/Jev 问题 schema/criteria(全英文)
├── src/test/                # GateLogic 7 条返回路径单测 + 注入回归 case
├── eval/
│   ├── labeled-samples.jsonl    # n=100
│   ├── RulesOnlyBaseline.java   ← v2 新增，M4 第一优先级
│   └── EvalRunner.java          # mock 模式硬失败
├── .env.example
├── .gitignore
└── README.md / README.zh-CN.md
```

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| Jev 在风控窄场景不如规则 | 架构上 Jev 只做分诊层；**M4 的规则基线对照把这个风险变成可发表的数据**——无论谁赢都有叙事 |
| **生态空窗以天计**（01-research §5：邻域已有加密交易、SOC 分诊项目，且有人在系统性 survey 金融方向） | 差异化说到"充提链路 + 自建校准数据"粒度，不泛说 Web3 风控；M1–M3 别拖 |
| early access 限速/字段变动 | 限速数值未核实，上线前看 console；M1 固化真实响应形状；mock 模式兜底演示 |
| confidence 饱和导致阈值扫描无意义 | M1 用 20 条探针先探，饱和就改做信度分布刻画（PRD §4.7.3），别硬做空表 |
| 样本自标、量小 | 报告首页顶格免责声明（PRD §4.7.1 原文）+ 每桶印 n；n<10 不解读 |
| **mock 模式产出假校准报告被截图外传** | EvalRunner mock 下硬失败 + 水印（M4） |
| state 注入（token 名/标签为攻击者可控） | `ExternalStringSanitizer` + eval 注入回归 case（M2/M4） |
| key 泄露 | `.env` 已在 `.gitignore` 首行；首次 `git add` 前再核一次 `git status` |

## 6. 发布前阻断项检查表

这 6 条不闭掉不发 README（来自 04-audit §5）：

- [ ] B1 全文无"100ms"类未实测承诺，延迟数字为 M1 实测值
- [ ] B2 GateLogic 里每处 confidence 都有明确下标，无裸 `confidence`
- [ ] B3 校准报告首页有免责声明，每桶印 n
- [ ] B4 mock 模式不产出可冒充真实的报告
- [ ] D2 action 语义矩阵已实现或已在 README 说明
- [ ] D8 端点默认仅本机 + README 标 demo-only

成功指标见 PRD §7（已拆为"交付动作"与"观察结果"两栏，本文不重复）。

