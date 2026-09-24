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
 * 所有流转都追加到 transitions，保证历史可追溯。
 */
@Component
public class AlertService {

    private final LocalStorage storage;

    public AlertService(LocalStorage storage) {
        this.storage = storage;
    }

    public synchronized List<Alert> process(BatchResult result, List<QualityRule> rules) {
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
            Alert updated;
            if (!outcome.isPassed()) {
                updated = current == null ? open(rule, at, batchId)
                        : current.getState() == AlertState.OPEN
                                ? merge(current, at, batchId)
                                : reopen(current, at, batchId);
            } else {
                updated = current != null && current.getState() == AlertState.OPEN
                        ? resolve(current, at, batchId)
                        : current;
            }
            if (updated != null) {
                storage.saveAlert(updated);
                touched.add(updated);
            }
        }
        return List.copyOf(touched);
    }

    private Alert open(QualityRule rule, Instant at, String batchId) {
        List<AlertTransition> transitions = new ArrayList<>();
        transitions.add(new AlertTransition(at, batchId, null, AlertState.OPEN,
                "批次 " + batchId + " 命中规则 " + rule.getRuleId() + "，首次打开告警"));
        return new Alert(rule.getRuleId(), rule.getRuleId(), AlertState.OPEN,
                batchId, batchId, 1, 1, at, null, transitions);
    }

    private Alert merge(Alert current, Instant at, String batchId) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.OPEN, AlertState.OPEN,
                "批次 " + batchId + " 再次越界，合并到既有告警（不重复打开）"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.OPEN,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount() + 1, current.getOpenCount(),
                current.getOpenedAt(), null, transitions);
    }

    private Alert reopen(Alert current, Instant at, String batchId) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.RESOLVED, AlertState.OPEN,
                "批次 " + batchId + " 恢复后再次越界，重新打开告警"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.OPEN,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount() + 1, current.getOpenCount() + 1,
                at, null, transitions);
    }

    private Alert resolve(Alert current, Instant at, String batchId) {
        List<AlertTransition> transitions = new ArrayList<>(current.getTransitions());
        transitions.add(new AlertTransition(at, batchId, AlertState.OPEN, AlertState.RESOLVED,
                "批次 " + batchId + " 指标恢复，告警关闭"));
        return new Alert(current.getAlertKey(), current.getRuleId(), AlertState.RESOLVED,
                current.getFirstBatchId(), batchId,
                current.getOccurrenceCount(), current.getOpenCount(),
                current.getOpenedAt(), at, transitions);
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
