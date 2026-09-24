package com.github.lisama569.dataqualitymonitorjava.model;

/** 单条规则对单个批次的判定结论，measured/expected/basis 用于可复现的判定依据。 */
public final class RuleOutcome {

    private final String ruleId;
    private final int ruleVersion;
    private final RuleType ruleType;
    private final String fieldName;
    private final boolean passed;
    private final String measured;
    private final String expected;
    private final String basis;

    public RuleOutcome(String ruleId, int ruleVersion, RuleType ruleType, String fieldName,
                       boolean passed, String measured, String expected, String basis) {
        this.ruleId = ruleId;
        this.ruleVersion = ruleVersion;
        this.ruleType = ruleType;
        this.fieldName = fieldName;
        this.passed = passed;
        this.measured = measured;
        this.expected = expected;
        this.basis = basis;
    }

    public String getRuleId() { return ruleId; }
    public int getRuleVersion() { return ruleVersion; }
    public RuleType getRuleType() { return ruleType; }
    public String getFieldName() { return fieldName; }
    public boolean isPassed() { return passed; }
    public String getMeasured() { return measured; }
    public String getExpected() { return expected; }
    public String getBasis() { return basis; }
}
