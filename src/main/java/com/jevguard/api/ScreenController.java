package com.jevguard.api;

import com.jevguard.domain.Action;
import com.jevguard.domain.Decision;
import com.jevguard.domain.Direction;
import com.jevguard.domain.TransferEvent;
import com.jevguard.log.DecisionLog;
import com.jevguard.service.ScreeningService;
import com.jevguard.simulator.EventSimulator;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST 端点（PRD §4.6，审计 D8）：
 * 默认绑 127.0.0.1（application.yml），demo-only，生产需前置网关鉴权。
 * /v1/decisions 默认地址中段脱敏。
 */
@RestController
@RequestMapping("/v1")
public class ScreenController {

    private final ScreeningService screening;
    private final DecisionLog decisionLog;
    private final EventSimulator simulator;

    public ScreenController(ScreeningService screening, DecisionLog decisionLog, EventSimulator simulator) {
        this.screening = screening;
        this.decisionLog = decisionLog;
        this.simulator = simulator;
    }

    public record ScreenRequest(String chain, String txHash, String direction,
                                String from, String to, String token, double amountUsd,
                                Long timestamp, Long addressFirstSeenAt, String counterpartyLabel) {}

    @PostMapping("/screen")
    public Decision screen(@RequestBody ScreenRequest r) {
        TransferEvent e = new TransferEvent(
                r.chain() == null ? "ethereum" : r.chain(),
                r.txHash(),
                "withdraw".equalsIgnoreCase(r.direction()) ? Direction.WITHDRAW : Direction.DEPOSIT,
                r.from(), r.to(),
                r.token() == null ? "USDT" : r.token(),
                r.amountUsd(),
                r.timestamp() == null ? System.currentTimeMillis() : r.timestamp(),
                r.addressFirstSeenAt(),
                r.counterpartyLabel() == null ? "unknown" : r.counterpartyLabel());
        return screening.screen(e);
    }

    /** 触发模拟器灌 N 笔混合事件流。 */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestParam(defaultValue = "20") int count) {
        List<Decision> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(screening.screen(simulator.next()));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", out.size());
        resp.put("actions", countByAction(out));
        resp.put("mockMode", screening.isMockMode());
        resp.put("decisions", out.stream().map(this::summary).toList());
        return resp;
    }

    @GetMapping("/decisions")
    public List<Map<String, Object>> decisions(@RequestParam(defaultValue = "50") int limit) {
        return decisionLog.recent(limit).stream().map(this::masked).toList();
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        List<Decision> all = decisionLog.all();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("actions", countByAction(all));
        m.put("degraded", all.stream().filter(Decision::degraded).count());
        // 延迟分位数区分 含重试/不含重试 两组（PRD §4.6.1）
        m.put("latencyMsNoRetry", percentiles(all.stream().filter(d -> !d.degraded() && (d.jev() == null || !d.jev().retried())).map(Decision::totalMs).toList()));
        m.put("latencyMsWithRetry", percentiles(all.stream().filter(d -> d.jev() != null && d.jev().retried()).map(Decision::totalMs).toList()));
        m.put("mockMode", screening.isMockMode());
        return m;
    }

    private Map<String, Long> countByAction(List<Decision> list) {
        Map<String, Long> c = new LinkedHashMap<>();
        for (Action a : Action.values()) c.put(a.name(), list.stream().filter(d -> d.action() == a).count());
        return c;
    }

    private Map<String, Object> percentiles(List<Long> values) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (values.isEmpty()) { p.put("n", 0); return p; }
        List<Long> sorted = values.stream().sorted().toList();
        p.put("n", sorted.size());
        p.put("p50", pct(sorted, 0.50));
        p.put("p95", pct(sorted, 0.95));
        p.put("max", sorted.get(sorted.size() - 1));
        return p;
    }

    private long pct(List<Long> sorted, double q) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1));
    }

    private Map<String, Object> summary(Decision d) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("direction", d.event().direction());
        s.put("amountUsd", d.event().amountUsd());
        s.put("action", d.action());
        s.put("reason", d.actionReason());
        s.put("totalMs", d.totalMs());
        return s;
    }

    /** 脱敏视图（地址中段打码）。 */
    private Map<String, Object> masked(Decision d) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", d.id());
        s.put("at", d.at());
        s.put("direction", d.event().direction());
        s.put("from", DecisionLog.maskAddress(d.event().from()));
        s.put("to", DecisionLog.maskAddress(d.event().to()));
        s.put("token", d.event().token());
        s.put("amountUsd", d.event().amountUsd());
        if (d.jev() != null) {
            Map<String, Object> j = new HashMap<>();
            j.put("riskLevel", d.jev().riskLevel().label);
            j.put("riskLevelConfidence", d.jev().riskLevelConfidence());
            j.put("pattern", d.jev().pattern());
            j.put("shouldFreeze", d.jev().shouldFreeze());
            j.put("route_observation_only", d.jev().route());
            s.put("jev", j);
        }
        s.put("hardRuleHit", d.hardRuleHit());
        s.put("action", d.action());
        s.put("reason", d.actionReason());
        s.put("totalMs", d.totalMs());
        s.put("degraded", d.degraded());
        return s;
    }
}
