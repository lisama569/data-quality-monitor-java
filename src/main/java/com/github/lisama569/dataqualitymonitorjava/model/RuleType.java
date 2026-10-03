package com.github.lisama569.dataqualitymonitorjava.model;

import com.github.lisama569.dataqualitymonitorjava.exception.UnknownMetricException;

/** 质量规则（指标）类型。 */
public enum RuleType {
    /** 完整性：字段非空（且可按契约类型解析）的行占比。 */
    COMPLETENESS,
    /** 唯一性：字段取值在批次内不重复的行占比（按 distinct/总行数计）。 */
    UNIQUENESS,
    /** 取值域：字段值落在允许集合或数值区间内的行占比。 */
    VALUE_DOMAIN,
    /** 新鲜度：数据自身业务时点到“判定时刻”的滞后秒数不超过阈值（迟到/回填批次照常判定）。 */
    FRESHNESS;

    /**
     * 按配置代码解析指标。null、空白或无法识别的代码一律抛出
     * UnknownMetricException，绝不能被静默跳过。
     */
    public static RuleType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new UnknownMetricException(String.valueOf(code));
        }
        String normalized = code.trim().toUpperCase();
        for (RuleType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new UnknownMetricException(code);
    }
}
