package com.jevguard.state;

import com.jevguard.domain.Direction;
import com.jevguard.domain.Features;
import com.jevguard.domain.TransferEvent;
import org.springframework.stereotype.Component;

/**
 * state 渲染器：英文键值行式特征摘要，不含原始 tx JSON（PRD §4.4a）。
 * 空值字段一律不渲染——空字段是纯噪声（01-research §4）。
 * 所有外部字符串已过 ExternalStringSanitizer。
 */
@Component
public class StateRenderer {

    private final ExternalStringSanitizer san;

    public StateRenderer(ExternalStringSanitizer san) {
        this.san = san;
    }

    public String render(TransferEvent e, Features f) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Crypto transfer screening request.\n");
        line(sb, "direction", e.direction() == Direction.DEPOSIT ? "deposit (already confirmed on-chain)" : "withdrawal (not yet broadcast, can be held)");
        line(sb, "chain", san.stripControl(e.chain()));
        line(sb, "token", san.token(e.token()));
        line(sb, "amount_usd", String.format("%.2f", e.amountUsd()));
        line(sb, "amount_band", f.amountBand());
        if (f.addressAgeDays() != null) line(sb, "address_age_days", String.valueOf(f.addressAgeDays()));
        else line(sb, "address_age", "unknown (no first-seen record)");
        line(sb, "transfers_24h_count", String.valueOf(f.count24h()));
        line(sb, "transfers_24h_volume_usd", String.format("%.2f", f.volume24hUsd()));
        if (f.minutesSinceLastTransfer() != null)
            line(sb, "minutes_since_last_transfer", String.valueOf(f.minutesSinceLastTransfer()));
        line(sb, "counterparty_label", san.label(f.counterpartyLabel()));
        line(sb, "first_interaction_with_counterparty", yesNo(f.firstInteraction()));
        line(sb, "same_amount_burst_within_1h", yesNo(f.sameAmountBurst1h()));
        line(sb, "night_operation_local_0_to_6am", yesNo(f.nightOperation()));
        line(sb, "blacklist_hit", yesNo(f.blacklistHit()));
        return sb.toString();
    }

    private void line(StringBuilder sb, String k, String v) {
        if (v == null || v.isBlank()) return; // 空值不渲染
        sb.append(k).append(": ").append(v).append('\n');
    }

    private String yesNo(boolean b) { return b ? "yes" : "no"; }
}
