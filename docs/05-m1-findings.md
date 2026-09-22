# M1/M3 真机实测发现（TypeSafe Jev API）

> 本文固化对 `POST https://api.typesafe.ai/v1/systemone` 的真机实测结论。
> 所有结论均来自真实调用（非文档转述），代码已按此适配。
> 实测日期：2026-09-23，模型实测返回 `jev-1.13.0`。

## 1. 请求/响应基线

- 端点：`POST /v1/systemone`，`Authorization: Bearer <key>`，HTTP 200。
- 请求体：`{model, state, questions}`。
- 响应体：

```json
{
  "model": "jev-1.13.0",
  "answers": { "...": "..." },
  "usage": { "input_tokens": 395, "output_tokens": 36 }
}
```

- 延迟：冷启动首发约 2.6s；稳态单次 240–830ms（M3 实测 8 笔，p50=272ms，p95=830ms，未重试）。
- Token：本场景 state+4 问约 395 input / 36 output tokens 每次。

## 2. 文档外的三个实测发现

### 2.1 noul 答案没有 confidence 字段

`should_freeze`（type=noul）的 answer 只有 `noul` 值本身，不返回 `confidence`。
代码派生：`freezeConfidence = |noul - 0.5| * 2`（越靠近 0/1 越确定）。
见 `JevAnswers.deriveNoulConfidence`。

### 2.2 score 的 legend/probabilities 是 0 基索引

`risk_level`（type=score，5 档 criteria）返回的 `probabilities` 键为 `"0".."4"`，
`0=normal … 4=critical`，即 **0 基**。
代码用 `RiskLevel.fromZeroBasedIndex(idx)` 转成内部 1 基序数（normal=1 … critical=5），
门控阈值 `highRiskOrdinal: 4` 对应 0 基的 `"3"`。

### 2.3 每种问题类型都要求 criteria（noul 除外）

不带 criteria 直接 422：

```
{"detail":[{"type":"missing","loc":["body","questions","risk_level","score","criteria"],"msg":"Field required", ...}]}
```

- score：criteria 必须是 **真 List**（`["normal: ...", "watch: ...", ...]`）。
- choice：criteria 是 **Map**（`{"auto_pass": "...", ...}`）。
- noul：不需要 criteria。

## 3. 因此暴露并修复的两个工程坑（M3）

1. **Spring Boot Binder 丢弃 JavaBean 中的 `Object` 复合属性**。
   `RiskRulesConfig.Question` 里 `Object criteria`（List 或 Map）被静默绑定为 null → 422 missing。
   修复：questions 改为 `Map<String, Map<String, Object>>` 透传。
2. **Binder 把 YAML 序列绑成索引 Map**。
   score 的 criteria list 进入 `Map<String,Object>` 后变成 `{"0": "...", "1": "..."}` → 422 list_type。
   修复：`ScreeningService.questionSchema()` 检测全数字键 Map 并按键序还原为 List。

教训：外部 API 的 schema 契约不要靠"绑定应该没问题"假设，必须在真机链路里验证。

## 4. 降级行为实测

- 上游 422/超时/5xx → 主链路降级 `MANUAL_REVIEW`，reason=`degraded: upstream unavailable`，
  不计入 Jev 延迟统计（M3 修复前两轮模拟曾真实触发，行为符合 PRD §5.2「宁可人审，不可漏放」）。
- 硬规则（黑名单命中）不调 Jev，`totalMs=0` 直接 FREEZE。

## 5. M3 端到端验证记录（真实 key，非 mock）

`POST /v1/simulate?count=8`，`mockMode=false`：

| 方向 | 金额(USD) | 动作 | 依据 | 耗时 |
|---|---|---|---|---|
| DEPOSIT | 9,493 | MANUAL_REVIEW | suspicious(ord=3) | 830ms |
| DEPOSIT | 5,844 | MANUAL_REVIEW | suspicious(ord=3) | 240ms |
| DEPOSIT | 146,982 | MANUAL_REVIEW | high_risk deposit 标记复查 | 286ms |
| DEPOSIT | 99,733 | MANUAL_REVIEW | suspicious(ord=3) | 302ms |
| WITHDRAW | 15,864 | FREEZE | 硬规则：黑名单命中 | 0ms |
| WITHDRAW | 11,434 | MANUAL_REVIEW | suspicious(ord=3) | 259ms |
| WITHDRAW | 19,658 | MANUAL_REVIEW | suspicious(ord=3) | 272ms |
| WITHDRAW | 13,282 | FREEZE | 高风险提现不降级（广播不可逆） | 292ms |

决策日志样例（`logs/decisions.jsonl`，地址已脱敏）：

```json
{"direction":"WITHDRAW","from":"0x6d8a...8793","jev":{"shouldFreeze":0.82,"riskLevel":"critical","route_observation_only":"manual_review","pattern":"mixer_linked","riskLevelConfidence":0.66},"action":"FREEZE","reason":"high_risk withdraw: freeze rather than downgrade (irreversible once broadcast)","totalMs":292}
```

- `route_observation_only` 只记录不参与路由（PRD §4.4，审计 D1），实测与门控结论一致性好坏留待 M4 校准评估量化。
- 本样本 AUTO_PASS=0 符合预期：模拟器 70% 正常事件金额都在数千 USD 的新地址上，Jev 给出 suspicious 是保守合理的；阈值是否过严由 M4 校准数据回答，不凭感觉调。
