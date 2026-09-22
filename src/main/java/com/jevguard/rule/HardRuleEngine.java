package com.jevguard.rule;

import com.jevguard.domain.Action;
import com.jevguard.domain.TransferEvent;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 硬规则层（PRD §4.3）：黑名单/制裁标签一票否决，不调 Jev。
 * 诚实声明：机制已实现，数据源未接入——名单是内置样例，非真实制裁名单源。
 */
@Component
public class HardRuleEngine {

    private final RiskRulesConfig rules;

    public HardRuleEngine(RiskRulesConfig rules) {
        this.rules = rules;
    }

    /** 返回硬规则动作；未命中返回 null。 */
    public HardRuleHit check(TransferEvent e) {
        String from = norm(e.from());
        String to = norm(e.to());
        for (String bad : rules.getHardRules().getBlacklist()) {
            if (norm(bad).equals(from) || norm(bad).equals(to)) {
                return new HardRuleHit(Action.FREEZE, "blacklist_hit:" + bad);
            }
        }
        if (rules.getHardRules().getSanctionedLabel().equals(e.counterpartyLabel())) {
            return new HardRuleHit(Action.FREEZE, "sanctioned_counterparty");
        }
        if (e.amountUsd() > rules.getHardRules().getMaxSingleTxUsd()) {
            return new HardRuleHit(Action.MANUAL_REVIEW, "amount_over_limit");
        }
        return null;
    }

    private String norm(String a) {
        return a == null ? "" : a.toLowerCase(Locale.ROOT);
    }

    public record HardRuleHit(Action action, String rule) {}
}
