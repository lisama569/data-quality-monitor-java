package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.model.Alert;
import com.github.lisama569.dataqualitymonitorjava.model.AlertState;
import com.github.lisama569.dataqualitymonitorjava.model.AlertTransition;
import com.github.lisama569.dataqualitymonitorjava.model.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.RuleOutcome;
import com.github.lisama569.dataqualitymonitorjava.storage.LocalStorage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 告警状态机（按规则维度维护，alertKey = ruleId）：
 * <ul>
 *   <li>规则首次失败：OPEN，openCount=1，记录 null -&gt; OPEN；</li>
 *   <li>已 OPEN 的规则再次失败：合并，只累加 occurrenceCount 与最近批次，不产生新告警；</li>
 *   <li>OPEN 规则在后续批次通过：RESOLVED，记录 OPEN -&gt; RESOLVED；</li>
 *   <li>RESOLVED 后再次失败：重新 OPEN，openCount+1，记录 RESOLVED -&gt; OPEN。</li>
 * </ul>
 * <b>业务时点水位（迟到/回填保护）</b>：每条告警记录已处理批次中最晚的业务时点。
 * 业务时点不晚于水位的迟到批次，无论越界与否都<b>不得翻转</b>已恢复/已打开的状态、
 * 不累计 occurrence/openCount，仅追加一条同态的“迟到抑制”流转记录留痕；
 * 业务时点确实更晚且越界的数据，仍按原有合并/重开规则流转。
 * 所有流转都追加到 transitions，保证历史可追溯。
 */
@Component
public class AlertService {

    private final LocalStorage storage;

    public AlertService(LocalStorage storage) {
        this.storage = storage;
    }

    /**
     * 处理单个已评估批次对各规则告警的影响。
     *
     * @param result       批次结论
     * @param rules        本次评估使用的规则快照（最新版本）
     * @param businessTime 批次数据自身的业务时点，作为迟到判定与水位推进依据
     */
    public synchronized List<Alert> process(BatchResult result, List<QualityRule> rules,
                                            Instant businessTime) {
        Instant at = result.getEvaluatedAt();
        String batchId = result.getBatchId();
        Map<String, RuleOutcome> outcomes = result.getOutcomes().stream()
                .collect(Collectors.toMap(RuleOutcome::getRuleId, Function.identity(), (a, b) -> a));
        List<Alert> touched = new ArrayList<>();
        for (QualityRule rule : rules) {
            RuleOutcome outcome = outcomes.get(rule.getRuleId());
            if (outcome == null) {
                continue;
            }
            Alert current = storage.findAlert(rule.getRuleId()).orElse(null);

            // 迟到/回填保护：业务时点不晚于已处理水位时，禁止状态翻转与计数累计。
            if (current != null && current.getWatermarkBusinessTime() != null
                    && !businessTime.isAfter(current.getWatermarkBusinessTime())) {
                Alert suppressed = suppressLate(current, at, batchId, businessTime, outcome.isPassed());
                storage.saveAlert(suppressed);
                touched.add(suppressed);
                continue;
            }

            Alert updated;
            if (!outcome.isPassed()) {
                updated = current == null ? open(rule, at, batchId, businessTime)
                        : current.getState() == AlertState.OPEN
                                ? merge(current, at, batchId, businessTime)
                                : reopen(current, at, batchId, businessTime);
            } else {
                updated = current != null && current.getState() == AlertState.OPEN
                        ? resolve(current, at, batchId, businessTime)
                        : advanceWatermarkOnly(current, businessTime);
            }
            if (updated != null) {
                storage.saveAlert(updated);
                touched.add(updated);
            }
        }
        return List.copyOf(touched);
    }

    /** 兼容旧签名：无显式业务时点时退化为按批次判定时刻（历史行为）。 */
    public List<Alert> process(BatchResult result, List<QualityRule> rules) {
        return process(result, rules, result.getJudgedAt() != null
                ? result.getJudgedAt() : result.getEvaluatedAt());
    }

    /**
     * 迟到批次抑制：状态、计数、first/last 批次均不变，仅追加同态留痕记录并保持水位。
     * 注意 lastBatchId 不更新为迟到批次，避免“最近越界批次”被旧数据覆盖。
     */
    private Alert suppressLate(Alert current, Instant at, String batchId,
                               Instant businessTime, boolean passed) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        String verb = passed ? "指标已通过" : "再次越界";
        transitions.add(new AlertTransition(at, batchId, current.getState(), current.getState(),
                "迟到/回填批次 " + batchId + "（业务时点=" + businessTime
                        + "）不晚于已处理水位 " + current.getWatermarkBusinessTime()
                        + "，" + verb + "，按业务时序抑制：不翻转告警、不累计次数"));
        return new Alert(current.getAlertKey(), current.getRuleId(), current.getState(),
                current.getFirstBatchId(), current.getLastBatchId(),
                current.getOccurrenceCount(), current.getOpenCount(),
                current.getOpenedAt(), current.getResolvedAt(),
                transitions, current.getWatermarkBusinessTime());
    }

    /** 业务时序更晚的通过批次但当前无 OPEN 告警：仅推进水位，不产生流转记录。 */
    private Alert advanceWatermarkOnly(Alert current, Instant businessTime) {
        if (current == null) {
            return null;
        }
        if (current.getWatermarkBusinessTime() != null
                && !businessTime.isAfter(current.getWatermarkBusinessTime())) {
            return null;
        }
        return new Alert(current.getAlertKey(), current.getRuleId(), current.getState(),
                current.getFirstBatchId(), current.getLastBatchId(),
                current.getOccurrenceCount(), current.getOpenCount(),
                current.getOpenedAt(), current.getResolvedAt(),
                current.getTransitions(), businessTime);
    }

    private Alert open(QualityRule rule, Instant at, String batchId, Instant businessTime) {
        List<AlertTransition> transitions = new ArrayList<>();
        transitions.add(new AlertTransition(at, batchId, null, AlertState.OPEN,
                "批次 " + batchId + "（业务时点=" + businessTime + "）命中规则 "
                        + rule.getRuleId() + "，首次打开告警"));
        return new Alert(rule.getRuleId(), rule.getRuleId(), AlertState.OPEN,
                batchId, batchId, 1, 1, at, null, transitions, businessTime);
    }

    private Alert merge(Alert current, Instant at, String batchId, Instant businessTime) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.OPEN, AlertState.OPEN,
                "批次 " + batchId + "（业务时点=" + businessTime + "）再次越界，"
                        + "合并到既有告警（不重复打开）"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.OPEN,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount() + 1, current.getOpenCount(),
                current.getOpenedAt(), null, transitions, businessTime);
    }

    private Alert reopen(Alert current, Instant at, String batchId, Instant businessTime) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.RESOLVED, AlertState.OPEN,
                "批次 " + batchId + "（业务时点=" + businessTime
                        + "）业务时序更晚且恢复后再次越界，重新打开告警"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.OPEN,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount() + 1, current.getOpenCount() + 1,
                at, null, transitions, businessTime);
    }

    private Alert resolve(Alert current, Instant at, String batchId, Instant businessTime) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.OPEN, AlertState.RESOLVED,
                "批次 " + batchId + "（业务时点=" + businessTime + "）指标恢复，告警关闭"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.RESOLVED,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount(), current.getOpenCount(),
                current.getOpenedAt(), at, transitions, businessTime);
    }

    public Optional<Alert> find(String alertKey) {
        return storage.findAlert(alertKey);
    }

    public List<Alert> list() {
        return storage.listAlerts();
    }

    public static String alertKey(RuleOutcome outcome) {
        return outcome.getRuleId();
    }
}
