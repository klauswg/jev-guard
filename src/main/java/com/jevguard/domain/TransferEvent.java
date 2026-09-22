package com.jevguard.domain;

/** 充提事件。addressFirstSeenAt 是输入字段，不是算出来的特征（PRD §4.1, D6）。 */
public record TransferEvent(
        String chain,
        String txHash,
        Direction direction,
        String from,
        String to,
        String token,
        double amountUsd,
        long timestamp,          // epoch millis
        Long addressFirstSeenAt, // epoch millis; 由链上索引/账户系统提供，模拟器合成
        String counterpartyLabel // 枚举：mixer / exchange / unknown / sanctioned
) {}
