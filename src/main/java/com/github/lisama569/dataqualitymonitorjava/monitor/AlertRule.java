package com.github.lisama569.dataqualitymonitorjava.monitor;

import com.github.lisama569.dataqualitymonitorjava.common.MetricName;

/**
 * 指标告警规则。operator 为 ">" 或 "<"；阈值变更生成新版本，历史告警保留旧版本结论以支持复现。
 */
public record AlertRule(String alertId, String dataset, MetricName metric, String operator, double threshold, int version) {
}
