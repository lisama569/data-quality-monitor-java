package com.github.lisama569.dataqualitymonitorjava.contract;

/** 质量规则类型，同时也是失败原因的一级归类。 */
public enum RuleType {
    COMPLETENESS,
    UNIQUENESS,
    VALUE_DOMAIN,
    FRESHNESS
}
