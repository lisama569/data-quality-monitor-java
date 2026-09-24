package com.github.lisama569.dataqualitymonitorjava.model;

/**
 * 批次总体状态：
 * PASSED  - 成功评估且全部规则通过；
 * FAILED  - 成功评估但至少一条规则失败（失败原因）；
 * REJECTED- 批次被拒绝、未纳入评估（拒绝原因，如结构漂移、非法规则）。
 */
public enum BatchStatus {
    PASSED,
    FAILED,
    REJECTED
}
