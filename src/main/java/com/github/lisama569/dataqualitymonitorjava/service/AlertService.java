package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.model.Alert;
import com.github.lisama569.dataqualitymonitorjava.model.AlertState;
import com.github.lisama569.dataqualitymonitorjava.model.AlertTransition;
import com.github.lisama569.dataqualitymonitorjava.model.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.RuleOutcome;
import com.github.lisama569.dataqualitymonitorjava.storage.LocalStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 所有流转都追加到 transitions，保证历史可追溯。
 * <p>
 * 迟到/回填保护：每条告警维护“业务时点水位”（最近一次驱动流转的批次业务时点）。
 * 业务时点早于水位的迟到批次只落批次结论，不驱动流转、不累计 occurrence，
 * 因此已恢复/关闭的告警不会被旧数据重新拉成打开；业务时点不早于水位的批次
 * 照常走打开/合并/恢复/重开，越界仍会重新打开。
 */
@Component
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final LocalStorage storage;

    public AlertService(LocalStorage storage) {
        this.storage = storage;
    }

    public synchronized List<Alert> process(BatchResult result, List<QualityRule> rules) {
        Instant at = result.getEvaluatedAt();
        Instant businessTime = result.getBusinessTime();
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
            // 迟到批次：业务时点早于告警水位，不得回翻状态、不得重复累计。
            if (current != null && current.getBusinessWatermark() != null
                    && businessTime.isBefore(current.getBusinessWatermark())) {
                log.info("批次 {} 业务时点 {} 早于规则 {} 的告警水位 {}，判定 passed={} 仅落批次结论，"
                                + "不驱动告警流转、不累计次数（当前状态 {} 保持不变）",
                        batchId, businessTime, rule.getRuleId(), current.getBusinessWatermark(),
                        outcome.isPassed(), current.getState());
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
                        : current;
            }
            if (updated != null) {
                storage.saveAlert(updated);
                touched.add(updated);
            }
        }
        return List.copyOf(touched);
    }

    private Alert open(QualityRule rule, Instant at, String batchId, Instant businessTime) {
        List<AlertTransition> transitions = new ArrayList<>();
        transitions.add(new AlertTransition(at, batchId, null, AlertState.OPEN,
                "批次 " + batchId + " 命中规则 " + rule.getRuleId() + "，首次打开告警"));
        return new Alert(rule.getRuleId(), rule.getRuleId(), AlertState.OPEN,
                batchId, batchId, 1, 1, at, null, businessTime, transitions);
    }

    private Alert merge(Alert current, Instant at, String batchId, Instant businessTime) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.OPEN, AlertState.OPEN,
                "批次 " + batchId + " 再次越界，合并到既有告警（不重复打开）"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.OPEN,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount() + 1, current.getOpenCount(),
                current.getOpenedAt(), null, businessTime, transitions);
    }

    private Alert reopen(Alert current, Instant at, String batchId, Instant businessTime) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.RESOLVED, AlertState.OPEN,
                "批次 " + batchId + " 恢复后再次越界，重新打开告警"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.OPEN,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount() + 1, current.getOpenCount() + 1,
                at, null, businessTime, transitions);
    }

    private Alert resolve(Alert current, Instant at, String batchId, Instant businessTime) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.OPEN, AlertState.RESOLVED,
                "批次 " + batchId + " 指标恢复，告警关闭"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.RESOLVED,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount(), current.getOpenCount(),
                current.getOpenedAt(), at, businessTime, transitions);
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
