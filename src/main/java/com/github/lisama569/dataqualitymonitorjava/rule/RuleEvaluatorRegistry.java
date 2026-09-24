package com.github.lisama569.dataqualitymonitorjava.rule;

import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;
import com.github.lisama569.dataqualitymonitorjava.exception.InvalidRuleException;
import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 按规则类型路由到对应评估器；未知规则类型直接报错而非静默跳过。 */
@Component
public class RuleEvaluatorRegistry {

    private final Map<RuleType, RuleEvaluator> evaluators = new EnumMap<>(RuleType.class);

    public RuleEvaluatorRegistry(List<RuleEvaluator> evaluatorBeans) {
        for (RuleEvaluator evaluator : evaluatorBeans) {
            RuleType type = switch (evaluator) {
                case CompletenessEvaluator ignored -> RuleType.COMPLETENESS;
                case UniquenessEvaluator ignored -> RuleType.UNIQUENESS;
                case ValueDomainEvaluator ignored -> RuleType.VALUE_DOMAIN;
                case FreshnessEvaluator ignored -> RuleType.FRESHNESS;
                default -> throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                        "未识别的规则评估器: " + evaluator.getClass().getName());
            };
            evaluators.put(type, evaluator);
        }
    }

    public RuleEvaluator of(RuleType type) {
        RuleEvaluator evaluator = evaluators.get(type);
        if (evaluator == null) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG, "缺少规则类型对应的评估器: " + type);
        }
        return evaluator;
    }
}
