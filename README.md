# jev-guard

**Real-time risk triage gateway for crypto exchange deposits & withdrawals — Jev (TypeSafe System One) as the triage layer, deterministic code as the judge.**

[中文 README](README.zh-CN.md)

Jev is a decision model that answers structured questions over a state. This project answers one question with it: *given a deposit/withdrawal and its 24h behavioral features, who should handle it — auto-pass, human review, or freeze?* The answers (risk level, behavioral pattern, freeze probability) come from Jev; the **thresholds, the routing matrix, and the final action stay in Java** — because that's where they can be unit-tested, audited, and tuned.

## Why this architecture

- **Jev triages, code decides.** A hard-rule layer (blacklist / sanctioned counterparty / amount cap) vetoes before any model call. The model never gets the last word.
- **High-risk withdrawals freeze, never downgrade.** Once broadcast, a withdrawal is irreversible; a high-risk deposit is already on-chain, so it becomes account review instead. The direction × action matrix encodes this asymmetry.
- **`route` is recorded, never executed.** Jev's own routing suggestion is kept as an observation-only column so we can measure how often "model routes directly" disagrees with "code composes the decision" (72% agreement on our calibration set — that 28% gap is exactly why composition stays in code).
- **Degrade to human review, never to pass.** Upstream timeout/5xx/429 → `MANUAL_REVIEW`. Better to annoy an analyst than to let one through.

## Architecture

```
transfer event
    │
    ▼
FeatureBuilder ── 24h window (Caffeine, in-memory): count/volume/burst/age/rapid-in-out
    │
    ▼
HardRuleEngine ── blacklist / sanctioned / >$500k  ──hit──► FREEZE / MANUAL_REVIEW (no model call)
    │ miss
    ▼
StateRenderer ── English key=value state, empty fields omitted,
    │            external strings whitelist-sanitized (prompt-injection defense)
    ▼
Jev (System One) ── 4 questions: risk_level(score) / pattern(choice) / should_freeze(noul) / route(observation only)
    │                                  failure → MANUAL_REVIEW (degraded)
    ▼
GateLogic ── pFreeze / cHigh / cLow / highRiskOrdinal, direction-aware
    ▼
Decision + JSONL audit log (addresses masked)
```

## Quickstart (mock mode, no API key)

Requires JDK 17+ and Maven.

```bash
git clone https://github.com/klauswg/jev-guard && cd jev-guard
mvn spring-boot:run

# second terminal
./demo.sh        # or .\demo.ps1 on Windows
```

You'll see 5 simulated deposit/withdraw events routed in real time, latency metrics, and the masked audit log. Mock mode returns deterministic fixture answers so the full pipeline runs offline.

## Real mode

```bash
cp .env.example .env   # put your TypeSafe API key in it
mvn spring-boot:run
```

The app reads `.env` at startup. With a key present, the real TypeSafe client is used (429 retry-after honored; timeout/5xx retried once with backoff; other 4xx fails fast into degraded mode).

## Endpoints

| Endpoint | Description |
|---|---|
| `POST /v1/screen` | Screen one transfer event |
| `POST /v1/simulate?count=N` | Run N simulated events through the pipeline |
| `GET /v1/decisions?limit=N` | Recent decisions (addresses masked) |
| `GET /v1/metrics` | Action counts, latency percentiles (no-retry vs retried tracked separately) |

Binds to `127.0.0.1:8080` by default — demo only, see limitations.

## Calibration (real API, n=100)

Three columns on the same labeled sample set (72 pattern / 18 boundary / 10 injection cases, synthetic events, author-labeled — see disclaimer in the [full report](docs/calibration-report.md)):

| Column | Accuracy | Missed freeze | False freeze |
|---|---|---|---|
| Rules-only baseline | **68.0%** | 0 | 2 |
| Jev triage gate | 50.0% | 0 | 0 |
| Rules + Jev combined | 50.0% | 0 | 0 |

What we learned (the honest version):

1. **The rules baseline beats Jev on this narrow synthetic task.** That is not an embarrassment — it's the validation of the architecture: the model is the triage layer, never the judge. Jev over-escalates (65/100 to manual review) but **never misses a freeze**.
2. **Confidence carries no ranking signal here.** It is *not* saturated (62% of samples fall in 0.5–0.7), yet the highest-confidence bucket is the *least* accurate (25%). Gate on risk ordinal + freeze probability; use confidence only as a low-confidence fallback.
3. **The freeze threshold sweep is flat** (50% at every pFreeze from 0.60 to 0.90) — accuracy is bounded by the risk-level ordinal, not the freeze threshold, so we changed nothing.
4. **`route` vs composed action: 72% agreement.** The 28% disagreement is the measurable value of keeping composition in code.

Reproduce:

```bash
export TYPESAFE_API_KEY=...
mvn compile exec:java -Dexec.mainClass=com.jevguard.eval.EvalRunner
# regenerate samples: --regenerate ; forced mock run (watermarked): --allow-mock
```

The eval harness **refuses to run in mock mode** without `--allow-mock` — a mock "calibration" would be fake evidence. Forced mock output is prefixed `MOCK-` and watermarked.

## Cost

Measured on the real API (2026-09-23, `jev-1.13.0`): ~395 input tokens + ~36 output tokens per screening (4 questions over a ~20-line state). 100 screenings = 75,268 input tokens. Steady-state latency p50 ≈ 272ms, p95 ≈ 830ms (measured, `GET /v1/metrics` prints the live numbers; vendor-reported range is 70–500ms).

## Design tradeoffs

- **Feature math in code, judgment in model.** All arithmetic (windows, bands, bursts) is deterministic Java; the model only sees the digested state.
- **Thresholds externalized** to `risk-rules.yml`; eval recommendations are applied by hand, never auto-written back (that would tune on our own eval set and then "validate" with it).
- **Prompt-injection defense**: token names/labels are attacker-controlled → whitelist sanitizer + 10 injection regression cases in the eval set. Injection never changed the routing of any labeled sample.
- **noul has no confidence** (measured): the API omits it, so freeze confidence is derived client-side as `|v − 0.5| × 2`. Score probabilities are **0-indexed** (also measured, undocumented). See [docs/05-m1-findings.md](docs/05-m1-findings.md).

## Limitations (read before citing)

1. Hard-rule layer: **mechanism implemented, data source not connected** — the blacklist is built-in sample data, not a real sanctions list.
2. In-memory profile: **restarting loses the 24h feature window**. A Redis profile is reserved as an interface, not yet load-tested.
3. Calibration samples are **synthetic events with author-assigned labels, n=100** — this measures pattern-recognition consistency, **not real-world money-laundering detection rate**.
4. REST endpoints are **demo-only, localhost by default**; production use needs authentication in front.
5. The 70–500ms latency range is **vendor-reported**; our numbers are self-measured with the method published above.
6. We deliberately cite **no third-party benchmark numbers** we cannot trace to a source.

## Docs

- [docs/01-research.md](docs/01-research.md) — ecosystem research & gap analysis
- [docs/02-prd.md](docs/02-prd.md) — product requirements
- [docs/03-dev-plan.md](docs/03-dev-plan.md) — milestones M1–M5
- [docs/04-audit.md](docs/04-audit.md) — the audit that shaped v2
- [docs/05-m1-findings.md](docs/05-m1-findings.md) — measured API facts (0-indexed scores, noul confidence, criteria requirements)
- [docs/calibration-report.md](docs/calibration-report.md) — full calibration report

## License

MIT
