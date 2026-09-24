package com.github.lisama569.dataqualitymonitorjava.batch;

import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;

/** 单条规则的判定结论。observed 为实际观测值（用于复现判定依据）。 */
public record RuleVerdict(String ruleId,
                          RuleType ruleType,
                          String field,
                          boolean passed,
                          double observed,
                          String detail) {
}
