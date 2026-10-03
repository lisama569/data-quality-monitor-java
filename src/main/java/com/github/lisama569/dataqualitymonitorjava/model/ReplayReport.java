package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 指定判定时刻的只读复盘报告。
 * 纯派生结果：同 (契约, 判定时刻) 重复复盘内容完全一致，且不改变任何批次结论、告警状态与流转历史。
 */
public record ReplayReport(String contractId,
                           Instant judgedAt,
                           int batchCount,
                           int violatingBatchCount,
                           List<ReplayBatchView> batches) {
}
