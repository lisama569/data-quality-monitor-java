package com.github.lisama569.dataqualitymonitorjava.contract;

import java.util.Map;

/**
 * 质量规则定义。params 承载阈值参数，例如：
 * completeness -> min(最低非空比例)
 * uniqueness   -> 无参数（字段即业务键）
 * value_domain -> allowed(枚举值) 或 min/max(数值区间)
 * freshness    -> maxAgeSeconds(最大允许延迟)
 */
public record QualityRule(String ruleId, RuleType ruleType, String field, Map<String, Object> params) {
}
