package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次批次导入提交。
 * businessTime 为数据自身的业务时点（新鲜度按它到判定时刻的滞后计算）；
 * 为兼容历史调用方允许为空，此时按 submittedAt 兜底。
 */
public final class BatchSubmission {

    private final String batchId;
    private final String contractId;
    private final Instant submittedAt;
    private final Instant businessTime;
    private final List<DataRow> rows;

    public BatchSubmission(String batchId, String contractId, Instant submittedAt,
                           Instant businessTime, List<DataRow> rows) {
        this.batchId = batchId;
        this.contractId = contractId;
        this.submittedAt = submittedAt;
        this.businessTime = businessTime;
        this.rows = rows;
    }

    /** 兼容旧调用方：未显式携带业务时点时以提交时间兜底。 */
    public BatchSubmission(String batchId, String contractId, Instant submittedAt, List<DataRow> rows) {
        this(batchId, contractId, submittedAt, submittedAt, rows);
    }

    public String getBatchId() { return batchId; }
    public String getContractId() { return contractId; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getBusinessTime() { return businessTime; }

    /** 新鲜度实际使用的业务时点：显式 businessTime 优先，缺省回退到 submittedAt。 */
    public Instant effectiveBusinessTime() {
        return businessTime != null ? businessTime : submittedAt;
    }

    public List<DataRow> getRows() { return rows; }
}
