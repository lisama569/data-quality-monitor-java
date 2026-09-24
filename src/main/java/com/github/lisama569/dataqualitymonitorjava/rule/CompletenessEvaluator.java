package com.github.lisama569.dataqualitymonitorjava.rule;

import com.github.lisama569.dataqualitymonitorjava.batch.RuleVerdict;
import com.github.lisama569.dataqualitymonitorjava.common.FailureReason;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 完整性：字段非空记录数 / 总行数，必须 >= params.min。
 * 空字符串同样视为缺失。判定依据（非空数、总数、阈值）写入 detail，保证可复现。
 */
@Component
public class CompletenessEvaluator implements RuleEvaluator {

    @Override
    public RuleVerdict evaluate(QualityRule rule, RuleEvaluationContext context) {
        int total = context.rowCount();
        int nonNull = 0;
        for (Map<String, Object> row : context.rows()) {
            Object value = row.get(rule.field());
            if (value != null && !(value instanceof String s && s.isEmpty())) {
                nonNull++;
            }
        }
        double ratio = total == 0 ? 1.0 : (double) nonNull / total;
        double min = ((Number) rule.params().get("min")).doubleValue();
        boolean passed = ratio >= min;
        String detail = passed
                ? "completeness " + String.format("%.4f", ratio) + " >= min " + min
                  + " (非空 " + nonNull + "/" + total + ")"
                : FailureReason.COMPLETENESS_BELOW_THRESHOLD.name()
                  + ": completeness " + String.format("%.4f", ratio) + " < min " + min
                  + " (非空 " + nonNull + "/" + total + ")";
        return new RuleVerdict(rule.ruleId(), rule.ruleType(), rule.field(), passed, ratio, detail);
    }
}
