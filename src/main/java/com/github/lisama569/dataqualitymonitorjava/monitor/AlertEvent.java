package com.github.lisama569.dataqualitymonitorjava.monitor;

import com.github.lisama569.dataqualitymonitorjava.common.MetricName;

/** 告警状态事件（打开/合并/恢复/重开），形成可追溯的状态流转时间线。 */
public record AlertEvent(String alertId,
                         String dataset,
                         MetricName metric,
                         int ruleVersion,
                         AlertState state,
                         double observedValue,
                         double threshold,
                         String batchId,
                         long timestamp,
                         String message) {
}
