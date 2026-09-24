package com.github.lisama569.dataqualitymonitorjava.batch;

import java.util.List;

/** 批次评估结论（随契约版本与内容哈希持久化）。 */
public record BatchResult(String dataset,
                          String batchId,
                          BatchStatus status,
                          int contractVersion,
                          String contentHash,
                          List<RuleVerdict> verdicts,
                          String rejectionReason,
                          String evaluatedAt,
                          boolean duplicate) {
}
