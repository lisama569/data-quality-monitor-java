package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.model.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.model.DataContract;
import com.github.lisama569.dataqualitymonitorjava.model.DataType;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.RuleOutcome;
import com.github.lisama569.dataqualitymonitorjava.model.RuleType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按契约对批次执行完整性、唯一性、取值域与新鲜度评估。
 * 所有比率指标均以“合格行数 / 批次总行数”口径计算（取值精确到小数点后 4 位），
 * 结论附 measured/expected/basis，保证判定依据可复现、可审计。
 */
@Component
public class QualityEvaluator {

    /** 以 referenceTime 作为“当前时间”评估新鲜度，保证结论与时间相关时仍可复现。 */
    public List<RuleOutcome> evaluate(BatchSubmission submission, DataContract contract,
                                      List<QualityRule> rules, Instant referenceTime) {
        List<RuleOutcome> outcomes = new ArrayList<>();
        for (QualityRule rule : rules) {
            outcomes.add(switch (rule.getType()) {
                case COMPLETENESS -> completeness(submission, contract, rule);
                case UNIQUENESS -> uniqueness(submission, rule);
                case VALUE_DOMAIN -> valueDomain(submission, contract, rule);
                case FRESHNESS -> freshness(submission, rule, referenceTime);
            });
        }
        return outcomes;
    }

    private RuleOutcome completeness(BatchSubmission submission, DataContract contract, QualityRule rule) {
        long total = submission.getRows().size();
        long valid = 0;
        long blank = 0;
        long unparsable = 0;
        DataType type = contract.field(rule.getFieldName())
                .map(schema -> schema.dataType()).orElse(DataType.STRING);
        for (var row : submission.getRows()) {
            String value = row.values().get(rule.getFieldName());
            if (value == null || value.isBlank()) {
                blank++;
            } else if (isParsable(type, value.trim())) {
                valid++;
            } else {
                unparsable++;
            }
        }
        double ratio = ratio(valid, total);
        double expected = rule.getThreshold();
        boolean passed = ratio >= expected;
        String basis = String.format("总行数=%d，有效非空=%d，空值=%d，类型不可解析=%d；"
                + "口径=有效非空/总行数=%.4f，阈值=%.4f，%s",
                total, valid, blank, unparsable, ratio, expected, passed ? "达标" : "不达标");
        return outcome(rule, passed, String.format("%.4f", ratio),
                String.format(">=%.4f", expected), basis);
    }

    private RuleOutcome uniqueness(BatchSubmission submission, QualityRule rule) {
        long total = submission.getRows().size();
        List<String> values = new ArrayList<>(Math.toIntExact(total));
        for (var row : submission.getRows()) {
            values.add(row.values().get(rule.getFieldName()));
        }
        Set<String> distinct = new HashSet<>(values);
        double ratio = ratio(distinct.size(), total);
        double expected = rule.getThreshold();
        boolean passed = ratio >= expected;
        long dupRows = total - distinct.size();
        String basis = String.format("总行数=%d，distinct 取值数=%d，重复行=%d；"
                + "口径=distinct/总行数=%.4f，阈值=%.4f，%s",
                total, distinct.size(), dupRows, ratio, expected, passed ? "达标" : "不达标");
        return outcome(rule, passed, String.format("%.4f", ratio),
                String.format(">=%.4f", expected), basis);
    }

    private RuleOutcome valueDomain(BatchSubmission submission, DataContract contract, QualityRule rule) {
        long total = submission.getRows().size();
        long inDomain = 0;
        long outDomain = 0;
        long nullCount = 0;
        for (var row : submission.getRows()) {
            String value = row.values().get(rule.getFieldName());
            if (value == null || value.isBlank()) {
                nullCount++;
                outDomain++;
                continue;
            }
            String v = value.trim();
            if (inAllowed(rule, v) || inNumericRange(rule, v)) {
                inDomain++;
            } else {
                outDomain++;
            }
        }
        double ratio = ratio(inDomain, total);
        double expected = rule.getThreshold() == null ? 1.0 : rule.getThreshold();
        boolean passed = ratio >= expected;
        String basis = String.format("总行数=%d，域内=%d，域外(含空值)=%d，其中空值=%d；"
                + "口径=域内/总行数=%.4f，阈值=%.4f，%s",
                total, inDomain, outDomain, nullCount, ratio, expected, passed ? "达标" : "不达标");
        return outcome(rule, passed, String.format("%.4f", ratio),
                String.format(">=%.4f", expected), basis);
    }

    private RuleOutcome freshness(BatchSubmission submission, QualityRule rule, Instant referenceTime) {
        Instant submittedAt = submission.getSubmittedAt();
        long ageSeconds = Duration.between(submittedAt, referenceTime).getSeconds();
        long maxAge = rule.getMaxAgeSeconds();
        boolean passed = ageSeconds <= maxAge;
        String basis = String.format("批次提交时间=%s，评估参考时间=%s，滞后=%d 秒，"
                + "允许最大滞后=%d 秒，%s",
                submittedAt, referenceTime, ageSeconds, maxAge,
                passed ? "在新鲜度窗口内" : "超出新鲜度窗口");
        return outcome(rule, passed, ageSeconds + "s", "<=" + maxAge + "s", basis);
    }

    private boolean inAllowed(QualityRule rule, String value) {
        return rule.getAllowedValues() != null && rule.getAllowedValues().contains(value);
    }

    private boolean inNumericRange(QualityRule rule, String value) {
        if (rule.getNumericMin() == null && rule.getNumericMax() == null) {
            return false;
        }
        BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (NumberFormatException e) {
            return false;
        }
        if (rule.getNumericMin() != null && number.compareTo(BigDecimal.valueOf(rule.getNumericMin())) < 0) {
            return false;
        }
        if (rule.getNumericMax() != null && number.compareTo(BigDecimal.valueOf(rule.getNumericMax())) > 0) {
            return false;
        }
        return true;
    }

    private boolean isParsable(DataType type, String value) {
        return switch (type) {
            case STRING -> true;
            case INTEGER -> value.matches("[+-]?\\d+");
            case DECIMAL -> {
                try {
                    new BigDecimal(value);
                    yield true;
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case BOOLEAN -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
            case TIMESTAMP -> {
                try {
                    Instant.parse(value);
                    yield true;
                } catch (DateTimeParseException e) {
                    yield false;
                }
            }
        };
    }

    private double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return Math.round((double) numerator / denominator * 10000.0) / 10000.0;
    }

    private RuleOutcome outcome(QualityRule rule, boolean passed,
                                String measured, String expected, String basis) {
        return new RuleOutcome(rule.getRuleId(), rule.getVersion(), rule.getType(),
                rule.getFieldName(), passed, measured, expected, basis);
    }
}
