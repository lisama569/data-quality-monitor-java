package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;

/** 批次处理结论。rejections 承载拒绝原因，outcomes 承载规则级失败原因。 */
public final class BatchResult {

    /** 一条拒绝原因及其明细。 */
    public record Rejection(RejectReason reason, String detail) {
    }

    private final String batchId;
    private final String contractId;
    private final BatchStatus status;
    private final String contentHash;
    private final boolean duplicate;
    private final List<Rejection> rejections;
    private final List<RuleOutcome> outcomes;
    private final List<QualityRule> ruleSnapshots;
    private final Instant evaluatedAt;

    public BatchResult(String batchId, String contractId, BatchStatus status, String contentHash,
                       boolean duplicate, List<Rejection> rejections, List<RuleOutcome> outcomes,
                       List<QualityRule> ruleSnapshots, Instant evaluatedAt) {
        this.batchId = batchId;
        this.contractId = contractId;
        this.status = status;
        this.contentHash = contentHash;
        this.duplicate = duplicate;
        this.rejections = rejections;
        this.outcomes = outcomes;
        this.ruleSnapshots = ruleSnapshots;
        this.evaluatedAt = evaluatedAt;
    }

    public String getBatchId() { return batchId; }
    public String getContractId() { return contractId; }
    public BatchStatus getStatus() { return status; }
    public String getContentHash() { return contentHash; }
    public boolean isDuplicate() { return duplicate; }
    public List<Rejection> getRejections() { return rejections; }
    public List<RuleOutcome> getOutcomes() { return outcomes; }
    public List<QualityRule> getRuleSnapshots() { return ruleSnapshots; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
}
