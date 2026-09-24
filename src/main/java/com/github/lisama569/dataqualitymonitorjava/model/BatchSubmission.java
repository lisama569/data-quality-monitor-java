package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;

/** 一次批次导入提交。 */
public final class BatchSubmission {

    private final String batchId;
    private final String contractId;
    private final Instant submittedAt;
    private final List<DataRow> rows;

    public BatchSubmission(String batchId, String contractId, Instant submittedAt, List<DataRow> rows) {
        this.batchId = batchId;
        this.contractId = contractId;
        this.submittedAt = submittedAt;
        this.rows = rows;
    }

    public String getBatchId() { return batchId; }
    public String getContractId() { return contractId; }
    public Instant getSubmittedAt() { return submittedAt; }
    public List<DataRow> getRows() { return rows; }
}
