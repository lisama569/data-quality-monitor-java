package com.github.lisama569.dataqualitymonitorjava.rule;

import com.github.lisama569.dataqualitymonitorjava.batch.RuleVerdict;
import com.github.lisama569.dataqualitymonitorjava.common.FailureReason;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 取值域：非空取值必须命中 allowed 枚举，或落在 [min,max] 数值区间。
 * 空值不在本规则统计口径内（归属完整性规则）。observed 为非空记录的合规比例，
 * detail 最多展示 10 个越界值作为判定依据。
 */
@Component
public class ValueDomainEvaluator implements RuleEvaluator {

    @Override
    public RuleVerdict evaluate(QualityRule rule, RuleEvaluationContext context) {
        Map<String, Object> params = rule.params();
        int checked = 0;
        int valid = 0;
        Set<String> offending = new HashSet<>();
        for (Map<String, Object> row : context.rows()) {
            Object value = row.get(rule.field());
            if (value == null || (value instanceof String s && s.isEmpty())) {
                continue;
            }
            checked++;
            if (isAllowed(value, params)) {
                valid++;
            } else if (offending.size() < 10) {
                offending.add(String.valueOf(value));
            }
        }
        double ratio = checked == 0 ? 1.0 : (double) valid / checked;
        boolean passed = valid == checked;
        String detail;
        if (passed) {
            detail = "value_domain 合规比例 " + String.format("%.4f", ratio) + " (校验 " + checked + ")";
        } else {
            detail = FailureReason.VALUE_OUT_OF_DOMAIN.name()
                    + ": 字段 " + rule.field() + " 有 " + (checked - valid) + "/" + checked
                    + " 个取值越界，样例=" + offending + "，合规比例=" + String.format("%.4f", ratio);
        }
        return new RuleVerdict(rule.ruleId(), rule.ruleType(), rule.field(), passed, ratio, detail);
    }

    @SuppressWarnings("unchecked")
    private boolean isAllowed(Object value, Map<String, Object> params) {
        if (params.containsKey("allowed")) {
            List<Object> allowed = (List<Object>) params.get("allowed");
            return allowed.contains(value)
                    || (value instanceof Number number && allowed.stream().anyMatch(a -> numericEquals(a, number)));
        }
        if (value instanceof Number number) {
            double v = number.doubleValue();
            if (params.containsKey("min") && v < ((Number) params.get("min")).doubleValue()) {
                return false;
            }
            if (params.containsKey("max") && v > ((Number) params.get("max")).doubleValue()) {
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean numericEquals(Object allowedValue, Number incoming) {
        return allowedValue instanceof Number allowedNumber
                && allowedNumber.doubleValue() == incoming.doubleValue();
    }
}
