package com.github.lisama569.dataqualitymonitorjava.monitor;

import com.github.lisama569.dataqualitymonitorjava.common.MetricName;

import java.util.List;

/** 某告警键（alertId+dataset）当前状态与历史事件。 */
public record AlertStatus(String alertId,
                          String dataset,
                          MetricName metric,
                          AlertState state,
                          int ruleVersion,
                          double lastValue,
                          long firedCount,
                          List<AlertEvent> events) {
}
