package com.github.lisama569.dataqualitymonitorjava.rule;

import com.github.lisama569.dataqualitymonitorjava.batch.RuleVerdict;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;

/** 单条质量规则的评估器。 */
@FunctionalInterface
public interface RuleEvaluator {

    RuleVerdict evaluate(QualityRule rule, RuleEvaluationContext context);
}
