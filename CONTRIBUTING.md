# CONTRIBUTING.md（放到 jev-guard / jev-suite 根目录；sasuke 可在此基础上增删）

# Contributing

Thanks for your interest. This project family follows one design rule above everything:

> **Jev judges, code decides.** Thresholds, vetoes, and routing matrices live in
> unit-tested code. The model is the triage layer, never the judge.

Every contribution is reviewed against that rule. A PR that moves a decision from
code into a prompt will not be merged, no matter how good the numbers look.

## Ground rules

1. **Degrade toward caution.** Any failure path (model unreachable, malformed evidence)
   must end in review/escalation, never auto-pass.
2. **Evals must not use mock data silently.** Calibration runners hard-fail without an
   API key; forced mock output is watermarked (`--allow-mock`). Do not "improve" an
   eval by making it pass offline.
3. **Evidence before judgment.** If evidence retrieval fails, the check fails closed.
4. **Never tune on the eval set.** Eval recommendations are applied by hand; do not
   auto-write tuned thresholds back into the same eval that produced them.

## Getting started

```bash
git clone <repo> && cd <repo>
mvn package          # JDK 17+ required
```

Run the demo in mock mode (no API key needed) before touching anything:

```bash
mvn spring-boot:run  # jev-guard
./demo.sh
```

## PR checklist

- [ ] `mvn package` passes
- [ ] New decision logic has unit tests (the decision is in code, so it can be tested)
- [ ] New external-text input goes through the whitelist sanitizer
- [ ] README/docs updated if behavior changed
- [ ] No third-party benchmark numbers without a traceable source

## Good first issues

Look for issues labeled [`good first issue`](https://github.com/klauswg/jev-suite/labels/good%20first%20issue) —
doc fixes, extra injection regression cases, and new mock fixtures are great starting points.
Comment on the issue before starting so we don't duplicate work.

## Code of conduct

Be direct, be honest about limitations, cite sources. Disagreement about design is
welcome; personal attacks are not.
