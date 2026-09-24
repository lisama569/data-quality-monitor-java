package com.github.lisama569.dataqualitymonitorjava.storage;

import com.github.lisama569.dataqualitymonitorjava.model.Alert;
import com.github.lisama569.dataqualitymonitorjava.model.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.model.DataContract;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内本地存储（模拟数据库）。
 * <ul>
 *   <li>批次结论按 batchId 唯一存放：重复提交拿到既有结论，不会重复累计指标；</li>
 *   <li>规则按 ruleId 维护全部版本：历史批次随结果保存规则快照，结论可复现；</li>
 *   <li>所有底层结构均为并发安全容器，配合提交侧锁支持并发重复提交。</li>
 * </ul>
 */
@Component
public class LocalStorage {

    private final Map<String, DataContract> contracts = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, QualityRule>> ruleVersions = new ConcurrentHashMap<>();
    private final Map<String, BatchResult> batchResults = new ConcurrentHashMap<>();
    private final Map<String, Alert> alerts = new ConcurrentHashMap<>();

    public void saveContract(DataContract contract) {
        contracts.put(contract.getContractId(), contract);
    }

    public Optional<DataContract> findContract(String contractId) {
        return Optional.ofNullable(contracts.get(contractId));
    }

    public List<DataContract> listContracts() {
        return List.copyOf(contracts.values());
    }

    /** 保存规则的某一版本；同一 ruleId 下版本号不可重复写。 */
    public void saveRule(QualityRule rule) {
        ruleVersions.computeIfAbsent(rule.getRuleId(), k -> new ConcurrentHashMap<>())
                .put(rule.getVersion(), rule);
    }

    public Optional<QualityRule> findRuleVersion(String ruleId, int version) {
        return Optional.ofNullable(ruleVersions.getOrDefault(ruleId, Map.of()).get(version));
    }

    public Optional<QualityRule> findLatestRule(String ruleId) {
        Map<Integer, QualityRule> versions = ruleVersions.get(ruleId);
        if (versions == null || versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(versions.get(versions.keySet().stream().max(Integer::compareTo).orElseThrow()));
    }

    /** 当前生效（最新版本）的全部规则，按 ruleId 排序以保证评估顺序稳定。 */
    public List<QualityRule> listLatestRules() {
        return ruleVersions.keySet().stream()
                .sorted()
                .map(this::findLatestRule)
                .flatMap(Optional::stream)
                .toList();
    }

    /** 指定契约下当前生效（最新版本）的规则，按 ruleId 排序。 */
    public List<QualityRule> listLatestRulesForContract(String contractId) {
        return listLatestRules().stream()
                .filter(r -> contractId.equals(r.getContractId()))
                .toList();
    }

    public void saveBatch(BatchResult result) {
        batchResults.putIfAbsent(result.getBatchId(), result);
    }

    public Map<String, BatchResult> batchResults() {
        return batchResults;
    }

    public Optional<BatchResult> findBatch(String batchId) {
        return Optional.ofNullable(batchResults.get(batchId));
    }

    /** 批次按评估时间（插入顺序基本等价）稳定排序。 */
    public List<BatchResult> listBatches() {
        return batchResults.values().stream()
                .sorted(Comparator.comparing(BatchResult::getEvaluatedAt))
                .toList();
    }

    public void saveAlert(Alert alert) {
        alerts.put(alert.getAlertKey(), alert);
    }

    public Optional<Alert> findAlert(String alertKey) {
        return Optional.ofNullable(alerts.get(alertKey));
    }

    public Map<String, Alert> alerts() {
        return alerts;
    }

    public List<Alert> listAlerts() {
        return alerts.values().stream()
                .sorted(Comparator.comparing(Alert::getAlertKey))
                .toList();
    }

    /** 清空全部数据，主要供测试重置。 */
    public void reset() {
        contracts.clear();
        ruleVersions.clear();
        batchResults.clear();
        alerts.clear();
    }
}
