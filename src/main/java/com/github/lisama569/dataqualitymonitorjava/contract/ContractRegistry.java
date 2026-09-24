package com.github.lisama569.dataqualitymonitorjava.contract;

import com.github.lisama569.dataqualitymonitorjava.exception.InvalidRuleException;
import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.rule.RuleValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 本地契约注册表：按数据集维护版本序列（从 1 开始递增）。
 * 当前版本用于新批次评估；历史版本可按版本号取回，支撑历史结论复现。
 * 注册时经 RuleValidator 校验，非法字段引用或阈值直接拒绝，绝不静默丢弃规则。
 */
@Component
public class ContractRegistry {

    private final RuleValidator ruleValidator;
    private final Map<String, List<DataContract>> versions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequence = new ConcurrentHashMap<>();

    public ContractRegistry(RuleValidator ruleValidator) {
        this.ruleValidator = ruleValidator;
    }

    public DataContract register(String dataset, List<FieldSpec> fields, List<QualityRule> rules) {
        if (dataset == null || dataset.isBlank()) {
            throw new InvalidRuleException(RejectReason.INVALID_RULE_CONFIG, "dataset 不能为空");
        }
        List<FieldSpec> safeFields = List.copyOf(fields);
        List<QualityRule> safeRules = List.copyOf(rules);
        int version = sequence.computeIfAbsent(dataset, k -> new AtomicInteger(0)).incrementAndGet();
        DataContract contract = new DataContract(dataset, version, safeFields, safeRules);
        ruleValidator.validate(contract);
        versions.computeIfAbsent(dataset, k -> new ArrayList<>()).add(contract);
        return contract;
    }

    public DataContract current(String dataset) {
        List<DataContract> list = versions.get(dataset);
        if (list == null || list.isEmpty()) {
            return null;
        }
        synchronized (list) {
            return list.get(list.size() - 1);
        }
    }

    public DataContract version(String dataset, int version) {
        List<DataContract> list = versions.get(dataset);
        if (list == null) {
            return null;
        }
        synchronized (list) {
            for (DataContract contract : list) {
                if (contract.version() == version) {
                    return contract;
                }
            }
        }
        return null;
    }
}
