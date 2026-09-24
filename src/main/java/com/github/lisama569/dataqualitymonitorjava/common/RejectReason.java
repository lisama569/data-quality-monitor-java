package com.github.lisama569.dataqualitymonitorjava.common;

/**
 * 拒绝原因：批次未被接受进入评估流程时使用，与规则失败原因 FailureReason 严格区分。
 */
public enum RejectReason {
    MISSING_FIELD,
    SCHEMA_DRIFT_UNKNOWN_FIELD,
    SCHEMA_DRIFT_MISSING_REQUIRED_FIELD,
    INVALID_RULE_CONFIG,
    UNKNOWN_METRIC,
    DUPLICATE_BATCH_CONFLICT,
    UNKNOWN_DATASET,
    EMPTY_BATCH
}
