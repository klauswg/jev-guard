package com.jevguard.domain;

import java.time.Instant;

/** 一笔完整决策记录，落 JSONL。 */
public record Decision(
        String id,
        Instant at,
        TransferEvent event,
        Features features,
        String hardRuleHit,      // 命中硬规则名，未命中为 null
        JevAnswers jev,          // 硬规则命中时为 null（不调 Jev）
        Action action,
        String actionReason,
        long featureMs,
        long jevMs,
        long totalMs,
        boolean degraded         // 上游失败降级为 MANUAL_REVIEW
) {}
