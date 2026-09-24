package com.github.lisama569.dataqualitymonitorjava.engine;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchStatus;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.batch.RuleVerdict;
import com.github.lisama569.dataqualitymonitorjava.contract.DataContract;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.rule.RuleEvaluationContext;
import com.github.lisama569.dataqualitymonitorjava.rule.RuleEvaluator;
import com.github.lisama569.dataqualitymonitorjava.rule.RuleEvaluatorRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对结构合规的批次按契约逐条执行规则（不跳过任何一条），汇总为 PASSED/FAILED。
 * 日志打印批次标识、命中规则编号与判定依据，便于从测试日志复现结论。
 */
@Component
public class QualityEvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(QualityEvaluationEngine.class);

    private final RuleEvaluatorRegistry registry;
    private final Clock clock;

    public QualityEvaluationEngine(RuleEvaluatorRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public BatchResult evaluate(BatchSubmission submission, DataContract contract, String contentHash, boolean duplicate) {
        long now = clock.millis();
        long eventTime = submission.eventTime() == null ? now : submission.eventTime();
        RuleEvaluationContext context = new RuleEvaluationContext(submission.rows(), eventTime, now);

        List<RuleVerdict> verdicts = new ArrayList<>();
        for (QualityRule rule : contract.rules()) {
            RuleEvaluator evaluator = registry.of(rule.ruleType());
            RuleVerdict verdict = evaluator.evaluate(rule, context);
            verdicts.add(verdict);
            log.info("[批次评估] batchId={} contract={}#v{} rule={} type={} field={} passed={} observed={} 依据={}",
                    submission.batchId(), contract.dataset(), contract.version(),
                    verdict.ruleId(), verdict.ruleType(), verdict.field(),
                    verdict.passed(), String.format("%.4f", verdict.observed()), verdict.detail());
        }

        boolean allPassed = verdicts.stream().allMatch(RuleVerdict::passed);
        BatchStatus status = allPassed ? BatchStatus.PASSED : BatchStatus.FAILED;
        BatchResult result = new BatchResult(
                submission.dataset(),
                submission.batchId(),
                status,
                contract.version(),
                contentHash,
                List.copyOf(verdicts),
                null,
                Instant.ofEpochMilli(now).toString(),
                duplicate);
        log.info("[批次结论] batchId={} status={} 规则数={} 失败数={}",
                submission.batchId(), status, verdicts.size(),
                verdicts.stream().filter(v -> !v.passed()).count());
        return result;
    }
}
