package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.Set;

/**
 * 质量规则定义（不可变），归属于某个数据契约。不同 RuleType 使用不同参数：
 * COMPLETENESS/UNIQUENESS 用 threshold；VALUE_DOMAIN 用 allowedValues 或 numericMin/Max；
 * FRESHNESS 用 maxAgeSeconds。每次修改都会产生新版本，评估结论随版本快照持久化。
 */
public final class QualityRule {

    private final String contractId;
    private final String ruleId;
    private final int version;
    private final RuleType type;
    private final String fieldName;
    private final Double threshold;
    private final Set<String> allowedValues;
    private final Long numericMin;
    private final Long numericMax;
    private final Long maxAgeSeconds;
    private final Instant updatedAt;

    public QualityRule(String contractId, String ruleId, int version, RuleType type, String fieldName,
                       Double threshold, Set<String> allowedValues,
                       Long numericMin, Long numericMax, Long maxAgeSeconds, Instant updatedAt) {
        this.contractId = contractId;
        this.ruleId = ruleId;
        this.version = version;
        this.type = type;
        this.fieldName = fieldName;
        this.threshold = threshold;
        this.allowedValues = allowedValues == null ? null : Set.copyOf(allowedValues);
        this.numericMin = numericMin;
        this.numericMax = numericMax;
        this.maxAgeSeconds = maxAgeSeconds;
        this.updatedAt = updatedAt;
    }

    public String getContractId() { return contractId; }
    public String getRuleId() { return ruleId; }
    public int getVersion() { return version; }
    public RuleType getType() { return type; }
    public String getFieldName() { return fieldName; }
    public Double getThreshold() { return threshold; }
    public Set<String> getAllowedValues() { return allowedValues; }
    public Long getNumericMin() { return numericMin; }
    public Long getNumericMax() { return numericMax; }
    public Long getMaxAgeSeconds() { return maxAgeSeconds; }
    public Instant getUpdatedAt() { return updatedAt; }
}
