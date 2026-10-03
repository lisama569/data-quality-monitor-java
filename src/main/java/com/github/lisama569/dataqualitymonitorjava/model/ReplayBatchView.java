package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 只读复盘下单个批次的派生查看结果（非落库结论）。
 * carriedStatus 为批次当时落库的真实状态；freshnessOutcomes 是按复盘判定时刻重算的新鲜度结论；
 * violatedRuleIds 为该口径下越界（不新鲜）的规则集合。
 */
public record ReplayBatchView(String batchId,
                              String contractId,
                              Instant businessTime,
                              BatchStatus carriedStatus,
                              Instant judgedAt,
                              List<RuleOutcome> freshnessOutcomes,
                              List<String> violatedRuleIds) {
}
