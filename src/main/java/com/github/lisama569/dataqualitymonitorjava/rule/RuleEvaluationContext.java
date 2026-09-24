package com.github.lisama569.dataqualitymonitorjava.rule;

import java.util.List;
import java.util.Map;

/** 规则评估时的输入上下文。 */
public record RuleEvaluationContext(List<Map<String, Object>> rows, long eventTime, long now) {

    public int rowCount() {
        return rows.size();
    }
}
