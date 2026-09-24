package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.model.DataContract;
import com.github.lisama569.dataqualitymonitorjava.model.DataType;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.RuleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验规则配置是否合法。任何违例都会作为非法规则明确拒绝，绝不静默跳过：
 * <ul>
 *   <li>通用：ruleId/type 非空；fieldName 必须是契约声明的列（FRESHNESS 作用于批次可除外）；</li>
 *   <li>COMPLETENESS/UNIQUENESS：必须提供 threshold 且在 [0,1]；</li>
 *   <li>VALUE_DOMAIN：必须提供 allowedValues 或 numericMin/numericMax；区间 min&lt;=max；
 *       数值区间只能用于 INTEGER/DECIMAL 字段；threshold 若提供须在 [0,1]，缺省按 1.0；</li>
 *   <li>FRESHNESS：必须提供正整数 maxAgeSeconds。</li>
 * </ul>
 */
@Component
public class RuleValidator {

    public List<String> validate(QualityRule rule, DataContract contract) {
        List<String> violations = new ArrayList<>();
        if (rule.getRuleId() == null || rule.getRuleId().isBlank()) {
            violations.add("ruleId 不能为空");
        }
        RuleType type = rule.getType();
        if (type == null) {
            violations.add("规则类型不能为空");
            return violations;
        }
        if (type != RuleType.FRESHNESS) {
            if (rule.getFieldName() == null || rule.getFieldName().isBlank()) {
                violations.add(type + " 规则必须指定 fieldName");
            } else if (contract != null && !contract.hasColumn(rule.getFieldName())) {
                violations.add("字段 " + rule.getFieldName() + " 不在契约 " + contract.getContractId() + " 中");
            }
        }
        switch (type) {
            case COMPLETENESS, UNIQUENESS -> validateThreshold(rule, violations);
            case VALUE_DOMAIN -> validateValueDomain(rule, contract, violations);
            case FRESHNESS -> {
                Long maxAge = rule.getMaxAgeSeconds();
                if (maxAge == null || maxAge <= 0) {
                    violations.add("FRESHNESS 规则必须提供正数 maxAgeSeconds");
                }
            }
        }
        return violations;
    }

    private void validateThreshold(QualityRule rule, List<String> violations) {
        Double t = rule.getThreshold();
        if (t == null) {
            violations.add(rule.getType() + " 规则必须提供 threshold");
        } else if (t < 0.0 || t > 1.0) {
            violations.add("threshold 必须在 [0,1] 区间内，实际为 " + t);
        }
    }

    private void validateValueDomain(QualityRule rule, DataContract contract, List<String> violations) {
        boolean hasAllowed = rule.getAllowedValues() != null && !rule.getAllowedValues().isEmpty();
        boolean hasRange = rule.getNumericMin() != null || rule.getNumericMax() != null;
        if (!hasAllowed && !hasRange) {
            violations.add("VALUE_DOMAIN 规则必须提供 allowedValues 或 numericMin/numericMax");
        }
        if (hasRange && rule.getNumericMin() != null && rule.getNumericMax() != null
                && rule.getNumericMin() > rule.getNumericMax()) {
            violations.add("数值区间非法: numericMin(" + rule.getNumericMin()
                    + ") > numericMax(" + rule.getNumericMax() + ")");
        }
        if (hasRange && contract != null) {
            contract.field(rule.getFieldName()).ifPresent(schema -> {
                DataType dt = schema.dataType();
                if (dt != DataType.INTEGER && dt != DataType.DECIMAL) {
                    violations.add("numericMin/numericMax 只能用于数值字段，字段 "
                            + schema.name() + " 的类型为 " + dt);
                }
            });
        }
        if (rule.getThreshold() != null && (rule.getThreshold() < 0.0 || rule.getThreshold() > 1.0)) {
            violations.add("threshold 必须在 [0,1] 区间内，实际为 " + rule.getThreshold());
        }
    }
}
