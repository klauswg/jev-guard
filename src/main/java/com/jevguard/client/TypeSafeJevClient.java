package com.jevguard.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevguard.domain.JevAnswers;
import com.jevguard.domain.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * TypeSafe Jev 真实客户端（M1）。
 * 错误分流（PRD §5.2，审计 D9）：
 *  - 429：读 retry-after，不占重试预算，等不起就直接抛 JevUnavailableException 走降级
 *  - 超时/5xx：退避重试 1 次；仍失败抛异常
 *  - 其他 4xx：不重试，直接抛异常
 * 响应形状已实测固化（docs/05-m1-findings.md）：
 *  - noul 无 confidence 字段 → 派生 |v-0.5|*2
 *  - score 的 legend/probabilities 是 0 基索引 → +1 转 1 基序数
 */
public class TypeSafeJevClient implements JevClient {

    private static final Logger log = LoggerFactory.getLogger(TypeSafeJevClient.class);
    private static final long MAX_RETRY_AFTER_MS = 5000;

    private final RestClient http;
    private final ObjectMapper om = new ObjectMapper();
    private final String model;

    public TypeSafeJevClient(String baseUrl, String apiKey, String model, int timeoutMs) {
        this.model = model;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeoutMs);
        rf.setReadTimeout(timeoutMs);
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public JevAnswers evaluate(String state, Map<String, Object> questions) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("state", state);
        body.put("questions", questions);

        long t0 = System.nanoTime();
        boolean retried = false;
        try {
            JsonNode resp = post(body);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return parse(resp, ms, false);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                // 429：读 retry-after，不占重试预算；等不起就降级
                long wait = parseRetryAfter(e);
                if (wait > 0 && wait <= MAX_RETRY_AFTER_MS) {
                    sleep(wait);
                    JsonNode resp = post(body);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    return parse(resp, ms, true);
                }
                throw new JevUnavailableException("429 rate limited, retry-after=" + wait + "ms", e);
            }
            if (status >= 500) {
                retried = true;
                sleep(500);
                try {
                    JsonNode resp = post(body);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    return parse(resp, ms, true);
                } catch (Exception e2) {
                    throw new JevUnavailableException("5xx after retry", e2);
                }
            }
            // 其他 4xx（参数/鉴权）：不重试
            throw new JevUnavailableException("4xx " + status + ": " + e.getResponseBodyAsString(), e);
        } catch (JevUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // 超时/网络：退避重试 1 次
            if (!retried) {
                sleep(500);
                try {
                    JsonNode resp = post(body);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    return parse(resp, ms, true);
                } catch (Exception e2) {
                    throw new JevUnavailableException("timeout after retry", e2);
                }
            }
            throw new JevUnavailableException("call failed", e);
        }
    }

    private JsonNode post(Map<String, Object> body) {
        try {
            String resp = http.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(om.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return om.readTree(resp);
        } catch (RestClientResponseException e) {
            throw e;
        } catch (Exception e) {
            throw new JevUnavailableException("http error", e);
        }
    }

    /** 解析实测响应形状。 */
    JsonNode parse0(String json) throws Exception { return om.readTree(json); }

    JevAnswers parse(JsonNode resp, long latencyMs, boolean retried) {
        JsonNode answers = resp.path("answers");

        // risk_level (score)：0 基 legend/probabilities，取 probabilities argmax +1 转序数
        JsonNode rl = answers.path("risk_level");
        int idx = argmax(rl.path("probabilities"));
        RiskLevel level;
        if (idx >= 0) {
            level = RiskLevel.fromZeroBasedIndex(idx);
        } else {
            double v = rl.path("score").asDouble(0);
            level = RiskLevel.fromZeroBasedIndex((int) Math.round(v));
        }
        double rlConf = rl.path("confidence").asDouble(0.5);

        JsonNode pat = answers.path("pattern");
        String pattern = pat.path("choice").asText("unknown");
        Double patConf = pat.has("confidence") ? pat.get("confidence").asDouble() : null;

        // noul 实测无 confidence → 派生
        double freeze = answers.path("should_freeze").path("noul").asDouble(0.0);
        double freezeConf = JevAnswers.deriveNoulConfidence(freeze);

        JsonNode route = answers.path("route");
        String routeChoice = route.path("choice").asText("unknown");
        Double routeConf = route.has("confidence") ? route.get("confidence").asDouble() : null;

        long inTok = resp.path("usage").path("input_tokens").asLong(0);
        return new JevAnswers(level, rlConf, pattern, patConf, freeze, freezeConf,
                routeChoice, routeConf, inTok, latencyMs, retried);
    }

    private int argmax(JsonNode probs) {
        if (!probs.isObject() || probs.isEmpty()) return -1;
        int best = -1;
        double bestV = -1;
        for (Iterator<Map.Entry<String, JsonNode>> it = probs.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> en = it.next();
            double v = en.getValue().asDouble();
            if (v > bestV) {
                bestV = v;
                try { best = Integer.parseInt(en.getKey()); } catch (NumberFormatException ignored) {}
            }
        }
        return best;
    }

    private long parseRetryAfter(RestClientResponseException e) {
        String h = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("retry-after") : null;
        if (h == null) return -1;
        try { return Long.parseLong(h.trim()) * 1000; } catch (NumberFormatException ex) { return -1; }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Override
    public boolean isMock() { return false; }

    /** 上游不可用：由上层降级为 MANUAL_REVIEW（宁可人审，不可漏放，PRD §5.2）。 */
    public static class JevUnavailableException extends RuntimeException {
        public JevUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }
}
