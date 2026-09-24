package com.github.lisama569.dataqualitymonitorjava.rule;

import com.github.lisama569.dataqualitymonitorjava.batch.RuleVerdict;
import com.github.lisama569.dataqualitymonitorjava.common.FailureReason;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 唯一性：业务键字段取值不得重复（空值不参与唯一性比较）。
 * observed 为唯一值比例 = 不同取值数 / 非空记录数；出现任意重复即失败，
 * detail 中列出重复键，作为可复现的判定依据。
 */
@Component
public class UniquenessEvaluator implements RuleEvaluator {

    @Override
    public RuleVerdict evaluate(QualityRule rule, RuleEvaluationContext context) {
        Map<Object, Integer> frequencies = new HashMap<>();
        int nonNull = 0;
        for (Map<String, Object> row : context.rows()) {
            Object value = row.get(rule.field());
            if (value == null || (value instanceof String s && s.isEmpty())) {
                continue;
            }
            nonNull++;
            frequencies.merge(value, 1, Integer::sum);
        }
        long duplicates = frequencies.values().stream().filter(count -> count > 1).count();
        double ratio = nonNull == 0 ? 1.0 : (double) frequencies.size() / nonNull;
        boolean passed = duplicates == 0;
        String detail;
        if (passed) {
            detail = "uniqueness " + String.format("%.4f", ratio) + "，未发现重复键 (非空 " + nonNull + ")";
        } else {
            String duplicatedKeys = frequencies.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .limit(10)
                    .map(entry -> entry.getKey() + "x" + entry.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            detail = FailureReason.UNIQUENESS_VIOLATION.name()
                    + ": 字段 " + rule.field() + " 存在 " + duplicates + " 组重复键 [" + duplicatedKeys + "]"
                    + "，唯一值比例=" + String.format("%.4f", ratio);
        }
        return new RuleVerdict(rule.ruleId(), rule.ruleType(), rule.field(), passed, ratio, detail);
    }
}
