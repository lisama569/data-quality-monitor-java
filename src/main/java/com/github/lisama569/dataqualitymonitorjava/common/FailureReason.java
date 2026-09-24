package com.github.lisama569.dataqualitymonitorjava.common;

import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;

/**
 * 规则失败原因（可区分的判定依据）。
 */
public enum FailureReason {
    COMPLETENESS_BELOW_THRESHOLD(RuleType.COMPLETENESS),
    UNIQUENESS_VIOLATION(RuleType.UNIQUENESS),
    VALUE_OUT_OF_DOMAIN(RuleType.VALUE_DOMAIN),
    FRESHNESS_STALE(RuleType.FRESHNESS);

    private final RuleType ruleType;

    FailureReason(RuleType ruleType) {
        this.ruleType = ruleType;
    }

    public RuleType ruleType() {
        return ruleType;
    }
}
