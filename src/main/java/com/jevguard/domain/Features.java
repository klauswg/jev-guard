package com.jevguard.domain;

/** 代码侧算出的特征（PRD §4.2）。空值字段不渲染进 state。 */
public record Features(
        String amountBand,      // dust/small/medium/large/whale
        Long addressAgeDays,    // 由 addressFirstSeenAt 相减得出，可能为 null
        int count24h,
        double volume24hUsd,
        Long minutesSinceLastTransfer,
        boolean blacklistHit,
        String counterpartyLabel,
        boolean firstInteraction,
        boolean sameAmountBurst1h, // 近 1h 同额多笔
        boolean nightOperation     // 本地时间 0-6 点
) {}
