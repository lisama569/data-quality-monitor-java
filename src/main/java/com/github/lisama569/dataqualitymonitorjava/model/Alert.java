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

    public Alert(String alertKey, String ruleId, AlertState state,
                 String firstBatchId, String lastBatchId,
                 int occurrenceCount, int openCount,
                 Instant openedAt, Instant resolvedAt,
                 List<AlertTransition> transitions) {
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
}
