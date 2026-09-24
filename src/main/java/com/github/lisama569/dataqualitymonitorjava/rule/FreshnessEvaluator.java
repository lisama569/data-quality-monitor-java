package com.github.lisama569.dataqualitymonitorjava.rule;

import com.github.lisama569.dataqualitymonitorjava.batch.RuleVerdict;
import com.github.lisama569.dataqualitymonitorjava.common.FailureReason;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import org.springframework.stereotype.Component;

/**
 * 新鲜度（批次级）：ageSeconds = (now - eventTime)/1000，必须 <= maxAgeSeconds。
 * 时间来自可替换时钟，测试中固定时钟即可让结论完全可复现。
 * observed 为实际延迟秒数。
 */
@Component
public class FreshnessEvaluator implements RuleEvaluator {

    @Override
    public RuleVerdict evaluate(QualityRule rule, RuleEvaluationContext context) {
        double maxAge = ((Number) rule.params().get("maxAgeSeconds")).doubleValue();
        double ageSeconds = Math.max(0.0, (context.now() - context.eventTime()) / 1000.0);
        boolean passed = ageSeconds <= maxAge;
        String detail = passed
                ? "freshness 延迟 " + String.format("%.1f", ageSeconds) + "s <= maxAge " + maxAge + "s"
                : FailureReason.FRESHNESS_STALE.name()
                  + ": 延迟 " + String.format("%.1f", ageSeconds) + "s > maxAge " + maxAge + "s"
                  + " (eventTime=" + context.eventTime() + ", now=" + context.now() + ")";
        return new RuleVerdict(rule.ruleId(), rule.ruleType(),
                rule.field() == null || rule.field().isBlank() ? null : rule.field(),
                passed, ageSeconds, detail);
    }
}
