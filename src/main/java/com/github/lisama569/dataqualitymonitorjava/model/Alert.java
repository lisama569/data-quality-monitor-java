package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;

/** 某条规则维度的质量告警及其合并/恢复/重开统计。 */
public final class Alert {

    private final String alertKey;
    private final String ruleId;
    private final AlertState state;
    private final String firstBatchId;
    private final String lastBatchId;
    private final int occurrenceCount;
    private final int openCount;
    private final Instant openedAt;
    private final Instant resolvedAt;
    private final List<AlertTransition> transitions;
    /**
     * 已处理批次中最晚的业务时点水位。
     * 只有业务时点严格更早（&lt; 水位）的迟到批次才被抑制：不得翻转（打开/关闭）本告警、
     * 不累计次数、不更新最近批次，只能留下一条抑制记录；业务时点等于水位（同一业务时点
     * 连续到达的多个批次）照常参与打开、合并、恢复、重开。
     */
    private final Instant watermarkBusinessTime;

    public Alert(String alertKey, String ruleId, AlertState state,
                 String firstBatchId, String lastBatchId,
                 int occurrenceCount, int openCount,
                 Instant openedAt, Instant resolvedAt,
                 List<AlertTransition> transitions,
                 Instant watermarkBusinessTime) {
        this.alertKey = alertKey;
        this.ruleId = ruleId;
        this.state = state;
        this.firstBatchId = firstBatchId;
        this.lastBatchId = lastBatchId;
        this.occurrenceCount = occurrenceCount;
        this.openCount = openCount;
        this.openedAt = openedAt;
        this.resolvedAt = resolvedAt;
        this.transitions = transitions;
        this.watermarkBusinessTime = watermarkBusinessTime;
    }

    /** 兼容旧签名：无业务时点水位（由状态机在流转时补齐）。 */
    public Alert(String alertKey, String ruleId, AlertState state,
                 String firstBatchId, String lastBatchId,
                 int occurrenceCount, int openCount,
                 Instant openedAt, Instant resolvedAt,
                 List<AlertTransition> transitions) {
        this(alertKey, ruleId, state, firstBatchId, lastBatchId, occurrenceCount, openCount,
                openedAt, resolvedAt, transitions, null);
    }

    public String getAlertKey() { return alertKey; }
    public String getRuleId() { return ruleId; }
    public AlertState getState() { return state; }
    public String getFirstBatchId() { return firstBatchId; }
    public String getLastBatchId() { return lastBatchId; }
    public int getOccurrenceCount() { return occurrenceCount; }
    public int getOpenCount() { return openCount; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public List<AlertTransition> getTransitions() { return transitions; }
    public Instant getWatermarkBusinessTime() { return watermarkBusinessTime; }
}
