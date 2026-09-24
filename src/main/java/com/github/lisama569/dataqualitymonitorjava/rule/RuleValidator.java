package com.github.lisama569.dataqualitymonitorjava.rule;

import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.contract.DataContract;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldType;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;
import com.github.lisama569.dataqualitymonitorjava.exception.InvalidRuleException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 契约注册前对字段定义与规则做合法性校验：
 * 字段重名/空名、规则编号缺失或重复、引用未知字段、阈值缺失或越界均直接拒绝，
 * 绝不静默跳过任何一条规则。
 */
@Component
public class RuleValidator {

    public void validate(DataContract contract) {
        validateFields(contract);
        Set<String> ruleIds = new HashSet<>();
        for (QualityRule rule : contract.rules()) {
            validateRule(contract, rule, ruleIds);
        }
    }

    private void validateFields(DataContract contract) {
        Set<String> names = new HashSet<>();
        for (FieldSpec field : contract.fields()) {
            if (field.name() == null || field.name().isBlank()) {
                throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "契约存在未命名字段: dataset=" + contract.dataset() + " v" + contract.version());
            }
            if (field.type() == null) {
                throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "字段 " + field.name() + " 缺少类型定义");
            }
            if (!names.add(field.name())) {
                throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "契约字段重名: " + field.name());
            }
        }
    }

    private void validateRule(DataContract contract, QualityRule rule, Set<String> ruleIds) {
        if (rule.ruleId() == null || rule.ruleId().isBlank()) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "存在缺少 ruleId 的规则: dataset=" + contract.dataset());
        }
        if (!ruleIds.add(rule.ruleId())) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "规则编号重复: " + rule.ruleId());
        }
        RuleType type = rule.ruleType();
        if (type == null) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "规则 " + rule.ruleId() + " 缺少 ruleType");
        }
        Map<String, Object> params = rule.params() == null ? Map.of() : rule.params();
        switch (type) {
            case COMPLETENESS -> validateCompleteness(contract, rule, params);
            case UNIQUENESS -> validateFieldRef(contract, rule);
            case VALUE_DOMAIN -> validateValueDomain(contract, rule, params);
            case FRESHNESS -> validateFreshness(contract, rule, params);
        }
    }

    private void validateFieldRef(DataContract contract, QualityRule rule) {
        if (rule.field() == null || !contract.hasField(rule.field())) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "规则 " + rule.ruleId() + " 引用了契约中不存在的字段: " + rule.field());
        }
    }

    private void validateCompleteness(DataContract contract, QualityRule rule, Map<String, Object> params) {
        validateFieldRef(contract, rule);
        double min = requireDouble(params, "min", rule.ruleId());
        if (min < 0.0 || min > 1.0) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "规则 " + rule.ruleId() + " 的 min 必须在 [0,1] 之间, 实际=" + min);
        }
    }

    private void validateValueDomain(DataContract contract, QualityRule rule, Map<String, Object> params) {
        validateFieldRef(contract, rule);
        boolean hasAllowed = params.containsKey("allowed");
        boolean hasRange = params.containsKey("min") || params.containsKey("max");
        if (!hasAllowed && !hasRange) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "规则 " + rule.ruleId() + " 的取值域必须提供 allowed 枚举或 min/max 区间");
        }
        if (hasAllowed) {
            Object allowed = params.get("allowed");
            if (!(allowed instanceof List<?> list) || list.isEmpty()) {
                throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "规则 " + rule.ruleId() + " 的 allowed 必须是非空列表");
            }
        }
        if (hasRange) {
            FieldSpec spec = contract.fieldSpec(rule.field());
            if (spec.type() != FieldType.LONG && spec.type() != FieldType.DOUBLE) {
                throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "规则 " + rule.ruleId() + " 对非数值字段 " + rule.field()
                                + "(" + spec.type() + ") 使用了 min/max 区间约束");
            }
            Double min = params.containsKey("min") ? requireDouble(params, "min", rule.ruleId()) : null;
            Double max = params.containsKey("max") ? requireDouble(params, "max", rule.ruleId()) : null;
            if (min != null && max != null && min > max) {
                throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "规则 " + rule.ruleId() + " 的 min(" + min + ") 大于 max(" + max + ")");
            }
        }
    }

    private void validateFreshness(DataContract contract, QualityRule rule, Map<String, Object> params) {
        // 新鲜度是批次级规则，field 可缺省；若提供则必须是契约中的时间戳字段
        if (rule.field() != null && !rule.field().isBlank()) {
            FieldSpec spec = contract.fieldSpec(rule.field());
            if (spec == null) {
                throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "规则 " + rule.ruleId() + " 引用了契约中不存在的字段: " + rule.field());
            }
            if (spec.type() != FieldType.TIMESTAMP) {
                throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "规则 " + rule.ruleId() + " 的新鲜度字段 " + rule.field() + " 必须是 TIMESTAMP 类型");
            }
        }
        double maxAge = requireDouble(params, "maxAgeSeconds", rule.ruleId());
        if (maxAge < 0.0) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "规则 " + rule.ruleId() + " 的 maxAgeSeconds 不能为负: " + maxAge);
        }
    }

    private double requireDouble(Map<String, Object> params, String key, String ruleId) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                "规则 " + ruleId + " 缺少数值型参数: " + key);
    }
}
