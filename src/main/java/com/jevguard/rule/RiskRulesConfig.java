package com.jevguard.rule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** risk-rules.yml 绑定（PRD：阈值全部外置）。 */
@Configuration
@ConfigurationProperties(prefix = "jevguard.rules")
public class RiskRulesConfig {

    private Map<String, Double> amountBands;
    private HardRules hardRules = new HardRules();
    private Gate gate = new Gate();
    // 注意：问题 schema 直接以 Map 透传。JavaBean 里声明 Object criteria 会被
    // Spring Binder 静默丢弃复合值（List/Map），导致上游 422（实测，M3）。
    private Map<String, Map<String, Object>> questions;

    public Map<String, Double> getAmountBands() { return amountBands; }
    public void setAmountBands(Map<String, Double> amountBands) { this.amountBands = amountBands; }
    public HardRules getHardRules() { return hardRules; }
    public void setHardRules(HardRules hardRules) { this.hardRules = hardRules; }
    public Gate getGate() { return gate; }
    public void setGate(Gate gate) { this.gate = gate; }
    public Map<String, Map<String, Object>> getQuestions() { return questions; }
    public void setQuestions(Map<String, Map<String, Object>> questions) { this.questions = questions; }

    public static class HardRules {
        private double maxSingleTxUsd;
        private List<String> blacklist;
        private String sanctionedLabel;
        public double getMaxSingleTxUsd() { return maxSingleTxUsd; }
        public void setMaxSingleTxUsd(double v) { this.maxSingleTxUsd = v; }
        public List<String> getBlacklist() { return blacklist; }
        public void setBlacklist(List<String> v) { this.blacklist = v; }
        public String getSanctionedLabel() { return sanctionedLabel; }
        public void setSanctionedLabel(String v) { this.sanctionedLabel = v; }
    }

    public static class Gate {
        private double pFreeze;
        private double cHigh;
        private double cLow;
        private int highRiskOrdinal;
        public double getPFreeze() { return pFreeze; }
        public void setPFreeze(double v) { this.pFreeze = v; }
        public double getCHigh() { return cHigh; }
        public void setCHigh(double v) { this.cHigh = v; }
        public double getCLow() { return cLow; }
        public void setCLow(double v) { this.cLow = v; }
        public int getHighRiskOrdinal() { return highRiskOrdinal; }
        public void setHighRiskOrdinal(int v) { this.highRiskOrdinal = v; }
    }
}
