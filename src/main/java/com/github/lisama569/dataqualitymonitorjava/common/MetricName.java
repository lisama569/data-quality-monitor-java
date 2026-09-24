package com.github.lisama569.dataqualitymonitorjava.common;

/**
 * 系统支持的可监控指标。告警规则引用未知指标时按 UNKNOWN_METRIC 拒绝。
 */
public enum MetricName {
    COMPLETENESS_RATIO,
    UNIQUENESS_RATIO,
    VALUE_DOMAIN_RATIO,
    FRESHNESS_AGE_SECONDS
}
