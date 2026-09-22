package com.jevguard.eval;

import com.jevguard.domain.Action;
import com.jevguard.domain.Direction;
import com.jevguard.domain.Features;
import com.jevguard.domain.TransferEvent;
import com.jevguard.rule.HardRuleEngine;

/**
 * 纯规则基线（PRD §4.7.2 第一列，审计 D10）：不调 Jev，
 * 硬规则 + 一组拍定的启发式打分。打分权重是作者拍的初始值，
 * 与 Jev 列同等待遇——谁也不许用自己的评估集调自己的参。
 *
 * 权重（报告里原文披露）：
 *   mixer 对手方 +4；地址年龄 <3 天 +2；24h 笔数 >=8 +1；24h 额 >10万 +1；
 *   1h 内同额连发 +2；30 分钟内同额进出（提现）+2；whale 档 +2 / large 档 +1；
 *   夜间 +1；首次交互 +1。
 *   总分 >=4 → FREEZE；2-3 → MANUAL_REVIEW；其余 AUTO_PASS。
 */
public class RulesOnlyBaseline {

    private final HardRuleEngine hardRules;

    public RulesOnlyBaseline(HardRuleEngine hardRules) {
        this.hardRules = hardRules;
    }

    public Action decide(TransferEvent e, Features f) {
        HardRuleEngine.HardRuleHit hit = hardRules.check(e);
        if (hit != null) return hit.action();

        int score = 0;
        if ("mixer".equals(f.counterpartyLabel())) score += 4;
        if (f.addressAgeDays() != null && f.addressAgeDays() < 3) score += 2;
        if (f.count24h() >= 8) score += 1;
        if (f.volume24hUsd() > 100_000) score += 1;
        if (f.sameAmountBurst1h()) score += 2;
        if (e.direction() == Direction.WITHDRAW
                && f.minutesSinceLastTransfer() != null && f.minutesSinceLastTransfer() < 30) score += 2;
        if ("whale".equals(f.amountBand())) score += 2;
        else if ("large".equals(f.amountBand())) score += 1;
        if (f.nightOperation()) score += 1;
        if (f.firstInteraction()) score += 1;

        if (score >= 4) return Action.FREEZE;
        if (score >= 2) return Action.MANUAL_REVIEW;
        return Action.AUTO_PASS;
    }
}
