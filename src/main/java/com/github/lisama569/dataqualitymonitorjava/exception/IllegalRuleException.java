package com.github.lisama569.dataqualitymonitorjava.exception;

import java.util.List;

/** 规则配置非法（阈值越界、参数缺失等），不得静默跳过。 */
public class IllegalRuleException extends DataQualityException {

    private final String ruleId;
    private final List<String> violations;

    public IllegalRuleException(String ruleId, List<String> violations) {
        super("规则非法: " + ruleId + " " + violations);
        this.ruleId = ruleId;
        this.violations = violations;
    }

    public String getRuleId() { return ruleId; }
    public List<String> getViolations() { return violations; }
}
