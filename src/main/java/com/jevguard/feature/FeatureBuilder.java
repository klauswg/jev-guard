package com.jevguard.feature;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jevguard.domain.Direction;
import com.jevguard.domain.Features;
import com.jevguard.domain.TransferEvent;
import com.jevguard.rule.RiskRulesConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 特征工程（PRD §4.2）：全部算术在代码侧，不用 Jev 算数。
 * 存储：Caffeine 进程内缓存——24h 窗口常驻堆内存，重启即丢窗口（README 必写）。
 */
@Component
public class FeatureBuilder {

    private static final long H24 = 24L * 3600_000;
    private static final long H1 = 3600_000L;

    private final RiskRulesConfig rules;
    /** address → 近期事件时间戳+金额（24h 窗口） */
    private final Cache<String, Deque<long[]>> window;
    private final Map<String, Boolean> seenCounterparties = new ConcurrentHashMap<>();

    public FeatureBuilder(RiskRulesConfig rules) {
        this.rules = rules;
        this.window = Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofHours(24))
                .maximumSize(1_000_000)
                .build();
    }

    public Features build(TransferEvent e) {
        String band = band(e.amountUsd());
        Long ageDays = e.addressFirstSeenAt() != null
                ? Math.max(0, (e.timestamp() - e.addressFirstSeenAt()) / 86_400_000L)
                : null;

        String acctKey = e.direction() == Direction.WITHDRAW ? e.from() : e.to();
        Deque<long[]> hist = window.get(acctKey, k -> new ArrayDeque<>());
        synchronized (hist) {
            long cutoff = e.timestamp() - H24;
            while (!hist.isEmpty() && hist.peekFirst()[0] < cutoff) hist.pollFirst();

            int count = hist.size();
            double vol = hist.stream().mapToDouble(r -> Double.longBitsToDouble(r[1])).sum();
            Long minutesSinceLast = hist.isEmpty() ? null : (e.timestamp() - hist.peekLast()[0]) / 60_000L;
            boolean sameAmountBurst = hist.stream()
                    .filter(r -> r[0] >= e.timestamp() - H1)
                    .filter(r -> Math.abs(Double.longBitsToDouble(r[1]) - e.amountUsd()) < 0.01)
                    .count() >= 2;

            hist.addLast(new long[]{e.timestamp(), Double.doubleToRawLongBits(e.amountUsd())});

            boolean night = isNight(e.timestamp());
            boolean firstInteraction = seenCounterparties.putIfAbsent(counterpartyKey(e), Boolean.TRUE) == null;

            return new Features(band, ageDays, count + 1, vol + e.amountUsd(),
                    minutesSinceLast, false, e.counterpartyLabel(), firstInteraction,
                    sameAmountBurst, night);
        }
    }

    private String counterpartyKey(TransferEvent e) {
        return e.direction() == Direction.WITHDRAW ? e.to() : e.from();
    }

    private boolean isNight(long ts) {
        LocalTime t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime();
        return t.getHour() < 6;
    }

    private String band(double usd) {
        Map<String, Double> b = rules.getAmountBands();
        if (usd <= b.get("dust")) return "dust";
        if (usd <= b.get("small")) return "small";
        if (usd <= b.get("medium")) return "medium";
        if (usd <= b.get("large")) return "large";
        return "whale";
    }
}
