package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次批次导入提交。
 * <ul>
 *   <li>businessTime：数据自身的业务时点，新鲜度滞后的起算点；缺省回退为 submittedAt，
 *       保证未升级业务时点口径的旧调用方行为不变；</li>
 *   <li>evaluationTime：调用方显式指定的判定时刻（复盘/测试用）；为 null 时由服务层
 *       在评估瞬间取系统当前时间。业务时点与判定时刻确定后，结论不随运行时刻漂移。</li>
 * </ul>
 */
public final class BatchSubmission {

    private final String batchId;
    private final String contractId;
    private final Instant submittedAt;
    private final Instant businessTime;
    private final Instant evaluationTime;
    private final List<DataRow> rows;

    public BatchSubmission(String batchId, String contractId, Instant submittedAt, List<DataRow> rows) {
        this(batchId, contractId, submittedAt, null, null, rows);
    }

    public BatchSubmission(String batchId, String contractId, Instant submittedAt,
                           Instant businessTime, Instant evaluationTime, List<DataRow> rows) {
        this.batchId = batchId;
        this.contractId = contractId;
        this.submittedAt = submittedAt;
        this.businessTime = businessTime != null ? businessTime : submittedAt;
        this.evaluationTime = evaluationTime;
        this.rows = rows;
    }

    public String getBatchId() { return batchId; }
    public String getContractId() { return contractId; }
    public Instant getSubmittedAt() { return submittedAt; }
    /** 业务时点（缺省已回退为 submittedAt，永不返回 null）。 */
    public Instant getBusinessTime() { return businessTime; }
    /** 调用方显式指定的判定时刻；null 表示由服务层取系统当前时间。 */
    public Instant getEvaluationTime() { return evaluationTime; }
    public List<DataRow> getRows() { return rows; }
}
