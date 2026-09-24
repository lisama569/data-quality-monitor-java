package com.github.lisama569.dataqualitymonitorjava.monitor;

import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.exception.InvalidRuleException;
import org.springframework.stereotype.Component;

/** 指标值与告警阈值的越界判定，operator 仅支持 ">" 与 "<"，其他写法注册时即拒绝。 */
@Component
public class AlertEvaluator {

    public boolean isBreaching(AlertRule rule, double value) {
        return switch (rule.operator()) {
            case ">" -> value > rule.threshold();
            case "<" -> value < rule.threshold();
            default -> throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "告警 " + rule.alertId() + " 使用了不支持的比较符: " + rule.operator());
        };
    }

    public void validateOperator(String operator) {
        if (!">".equals(operator) && !"<".equals(operator)) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG,
                    "告警比较符仅支持 '>' 或 '<'，实际: " + operator);
        }
    }
}
