package com.github.lisama569.dataqualitymonitorjava.monitor;

import com.github.lisama569.dataqualitymonitorjava.common.MetricName;
import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.exception.InvalidRuleException;
import com.github.lisama569.dataqualitymonitorjava.store.MetricsStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 告警注册与状态流转（内存模拟本地监控）。
 *
 * 状态机：
 *  - 首次越界：OK -> FIRING，记录 OPENED 事件；
 *  - 持续越界：保持 FIRING，合并为 SUPPRESSED 事件（重复告警不重复打开）；
 *  - 恢复正常：FIRING -> RESOLVED，记录 RESOLVED 事件；
 *  - 恢复后再次越界：RESOLVED -> FIRING，记录 REOPENED 事件。
 *
 * 阈值/比较符变更生成新版本规则；已经写出的事件携带当时的 ruleVersion，历史结论不变、可复现。
 */
@Component
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final MetricsStore metricsStore;
    private final AlertEvaluator alertEvaluator;

    private final Map<String, List<AlertRule>> ruleVersions = new ConcurrentHashMap<>();
    private final Map<String, MutableStatus> statuses = new ConcurrentHashMap<>();

    public AlertService(MetricsStore metricsStore, AlertEvaluator alertEvaluator) {
        this.metricsStore = metricsStore;
        this.alertEvaluator = alertEvaluator;
    }

    private static String key(String dataset, String alertId) {
        return dataset + "|" + alertId;
    }

    public synchronized AlertRule register(String alertId, String dataset, MetricName metric,
                                           String operator, double threshold) {
        if (alertId == null || alertId.isBlank()) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG, "alertId 不能为空");
        }
        if (metric == null) {
            throw new InvalidRuleException(RejectReason.UNKNOWN_METRIC,
                    "告警 " + alertId + " 引用了未知指标: null（合法值: " + List.of(MetricName.values()) + "）");
        }
        alertEvaluator.validateOperator(operator);
        String key = key(dataset, alertId);
        List<AlertRule> versions = ruleVersions.computeIfAbsent(key, k -> new ArrayList<>());
        int version = versions.size() + 1;
        AlertRule rule = new AlertRule(alertId, dataset, metric, operator, threshold, version);
        versions.add(rule);
        log.info("[告警注册] alertId={} dataset={} metric={} {} {} version={}",
                alertId, dataset, metric, operator, threshold, version);
        return rule;
    }

    /**
     * 批次评估沉淀指标后调用：对数据集内全部告警规则取该批次对应指标做一次状态流转。
     * 返回本次产生的事件列表（含合并事件）。
     */
    public synchronized List<AlertEvent> evaluateBatch(String dataset, String batchId, long timestamp) {
        List<AlertEvent> produced = new ArrayList<>();
        ruleVersions.forEach((key, versions) -> {
            if (!key.startsWith(dataset + "|")) {
                return;
            }
            AlertRule rule = versions.get(versions.size() - 1);
            Optional<MetricsStore.MetricSample> sample =
                    metricsStore.series(dataset, rule.metric()).stream()
                            .filter(s -> s.batchId().equals(batchId))
                            .findFirst();
            sample.ifPresent(s -> produced.add(transition(rule, s.value(), batchId, timestamp)));
        });
        return List.copyOf(produced);
    }

    private AlertEvent transition(AlertRule rule, double value, String batchId, long timestamp) {
        String key = key(rule.dataset(), rule.alertId());
        MutableStatus status = statuses.computeIfAbsent(key,
                k -> new MutableStatus(AlertState.OK, rule.version(), 0L));
        status.ruleVersion = rule.version();
        status.lastValue = value;

        boolean breaching = alertEvaluator.isBreaching(rule, value);
        AlertEvent event;
        if (breaching) {
            if (status.state == AlertState.FIRING) {
                event = new AlertEvent(rule.alertId(), rule.dataset(), rule.metric(), rule.version(),
                        AlertState.FIRING, value, rule.threshold(), batchId, timestamp,
                        "SUPPRESSED: 仍处于越界状态，重复告警已合并 ("
                                + value + " " + rule.operator() + " " + rule.threshold() + ")");
                log.info("[告警合并] alertId={} dataset={} batchId={} value={} threshold={}",
                        rule.alertId(), rule.dataset(), batchId, value, rule.threshold());
            } else {
                String action = status.state == AlertState.RESOLVED ? "REOPENED" : "OPENED";
                status.state = AlertState.FIRING;
                status.firedCount++;
                event = new AlertEvent(rule.alertId(), rule.dataset(), rule.metric(), rule.version(),
                        AlertState.FIRING, value, rule.threshold(), batchId, timestamp,
                        action + ": 指标越界 (" + value + " " + rule.operator() + " " + rule.threshold()
                                + ")，触发次数=" + status.firedCount);
                log.info("[告警打开] alertId={} dataset={} batchId={} action={} value={} threshold={} firedCount={}",
                        rule.alertId(), rule.dataset(), batchId, action, value, rule.threshold(),
                        status.firedCount);
            }
        } else {
            if (status.state == AlertState.FIRING) {
                status.state = AlertState.RESOLVED;
                event = new AlertEvent(rule.alertId(), rule.dataset(), rule.metric(), rule.version(),
                        AlertState.RESOLVED, value, rule.threshold(), batchId, timestamp,
                        "RESOLVED: 指标恢复到阈值内 (" + value + " " + rule.operator() + " " + rule.threshold() + ")");
                log.info("[告警恢复] alertId={} dataset={} batchId={} value={} threshold={}",
                        rule.alertId(), rule.dataset(), batchId, value, rule.threshold());
            } else {
                status.state = AlertState.OK;
                event = new AlertEvent(rule.alertId(), rule.dataset(), rule.metric(), rule.version(),
                        AlertState.OK, value, rule.threshold(), batchId, timestamp,
                        "OK: 指标在阈值内 (" + value + " " + rule.operator() + " " + rule.threshold() + ")");
            }
        }
        status.events.add(event);
        return event;
    }

    public synchronized Optional<AlertStatus> status(String dataset, String alertId) {
        MutableStatus status = statuses.get(key(dataset, alertId));
        if (status == null) {
            return Optional.empty();
        }
        return Optional.of(toSnapshot(alertId, dataset, status));
    }

    public synchronized List<AlertStatus> list(String dataset) {
        List<AlertStatus> result = new ArrayList<>();
        statuses.forEach((key, status) -> {
            if (key.startsWith(dataset + "|")) {
                String alertId = key.substring(dataset.length() + 1);
                result.add(toSnapshot(alertId, dataset, status));
            }
        });
        return List.copyOf(result);
    }

    private AlertStatus toSnapshot(String alertId, String dataset, MutableStatus status) {
        AlertRule current = ruleVersions.get(key(dataset, alertId)).get(status.ruleVersion - 1);
        return new AlertStatus(alertId, dataset, current.metric(), status.state, status.ruleVersion,
                status.lastValue, status.firedCount, List.copyOf(status.events));
    }

    /** 可变内部状态，仅在 synchronized 方法内访问。 */
    private static final class MutableStatus {
        private AlertState state;
        private int ruleVersion;
        private double lastValue;
        private long firedCount;
        private final List<AlertEvent> events = new ArrayList<>();

        private MutableStatus(AlertState state, int ruleVersion, long firedCount) {
            this.state = state;
            this.ruleVersion = ruleVersion;
            this.firedCount = firedCount;
        }
    }
}
