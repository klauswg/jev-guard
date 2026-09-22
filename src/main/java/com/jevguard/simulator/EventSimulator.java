package com.jevguard.simulator;

import com.jevguard.domain.Direction;
import com.jevguard.domain.TransferEvent;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 事件流模拟器（PRD §4.1）：8 类事件，约 70% 正常 / 30% 风险。
 * 生成器事件类型与 pattern 选项的对应关系见 PRD §4.1 表格；
 * 黑名单命中类不调 Jev（硬规则一票否决）。
 */
@Component
public class EventSimulator {

    private final Random rnd = new Random(42);
    private long seq = 0;

    private static final String[] TOKENS = {"USDT", "USDC", "ETH", "WBTC"};

    public TransferEvent next() {
        seq++;
        long now = System.currentTimeMillis();
        double roll = rnd.nextDouble();

        if (roll < 0.35) return normal(now, Direction.DEPOSIT);
        if (roll < 0.65) return normal(now, Direction.WITHDRAW);
        if (roll < 0.70) return blacklistHit(now);
        if (roll < 0.76) return structuring(now);
        if (roll < 0.82) return rapidInOut(now);
        if (roll < 0.88) return mixerLinked(now);
        if (roll < 0.94) return newAddressBurst(now);
        return dustProbe(now);
    }

    private TransferEvent normal(long now, Direction dir) {
        return new TransferEvent("ethereum", tx(), dir, addr(), addr(), token(),
                200 + rnd.nextDouble() * 20_000, now,
                now - (30L + rnd.nextInt(700)) * 86_400_000L, // 老地址
                rnd.nextBoolean() ? "exchange" : "unknown");
    }

    private TransferEvent blacklistHit(long now) {
        return new TransferEvent("ethereum", tx(), Direction.WITHDRAW, addr(),
                "0x0000000000000000000000000000000000000bad", token(),
                5_000 + rnd.nextDouble() * 50_000, now,
                now - 400L * 86_400_000L, "unknown");
    }

    private TransferEvent structuring(long now) {
        return new TransferEvent("ethereum", tx(), Direction.DEPOSIT, addr(), addr(), token(),
                9_000 + rnd.nextDouble() * 900, // 紧贴档位线下
                now, now - 2L * 86_400_000L, "unknown");
    }

    private TransferEvent rapidInOut(long now) {
        return new TransferEvent("ethereum", tx(), Direction.WITHDRAW, addr(), addr(), token(),
                20_000 + rnd.nextDouble() * 30_000, now,
                now - 5L * 86_400_000L, "unknown");
    }

    private TransferEvent mixerLinked(long now) {
        return new TransferEvent("ethereum", tx(), Direction.WITHDRAW, addr(), addr(), token(),
                10_000 + rnd.nextDouble() * 80_000, now,
                now - 100L * 86_400_000L, "mixer");
    }

    private TransferEvent newAddressBurst(long now) {
        return new TransferEvent("ethereum", tx(), Direction.DEPOSIT, addr(), addr(), token(),
                50_000 + rnd.nextDouble() * 100_000, now,
                now - 3_600_000L, // 1 小时前首次出现
                "unknown");
    }

    private TransferEvent dustProbe(long now) {
        return new TransferEvent("ethereum", tx(), Direction.DEPOSIT, addr(), addr(), token(),
                1 + rnd.nextDouble() * 8, now,
                now - 86_400_000L, "unknown");
    }

    private String token() { return TOKENS[rnd.nextInt(TOKENS.length)]; }

    private String addr() {
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < 40; i++) sb.append("0123456789abcdef".charAt(rnd.nextInt(16)));
        return sb.toString();
    }

    private String tx() {
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < 16; i++) sb.append("0123456789abcdef".charAt(rnd.nextInt(16)));
        return sb.toString() + "-" + seq;
    }
}
