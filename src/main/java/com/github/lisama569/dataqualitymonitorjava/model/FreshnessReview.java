package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 指定判定时刻下的新鲜度只读复盘结果（纯派生视图）。
 * 由批次落库的业务时点、判定时刻与规则版本快照重算得到，
 * 不改写任何已落地的批次结论、告警状态与流转历史；
 * 同一 reviewTime 重复复盘结果完全一致（不含生成时刻等运行期字段）。
 */
public record FreshnessReview(String contractId, Instant reviewTime,
                              int batchCount, int violatedCount,
                              List<Entry> entries) {

    /** 单个批次在复盘时刻口径下的新鲜度结论。 */
    public record Entry(String batchId, BatchStatus status,
                        Instant businessTime, Instant evaluatedAt,
                        boolean violated, List<Assessment> assessments) {
    }

    /** 单条新鲜度规则在复盘时刻口径下的重算结论。 */
    public record Assessment(String ruleId, int ruleVersion,
                             long lagSeconds, long maxAgeSeconds,
                             boolean passed, String basis) {
    }
}
