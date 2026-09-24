package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchStatus;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.batch.RuleVerdict;
import com.github.lisama569.dataqualitymonitorjava.common.MetricName;
import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.common.TimeService;
import com.github.lisama569.dataqualitymonitorjava.contract.ContractRegistry;
import com.github.lisama569.dataqualitymonitorjava.contract.DataContract;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;
import com.github.lisama569.dataqualitymonitorjava.engine.QualityEvaluationEngine;
import com.github.lisama569.dataqualitymonitorjava.engine.SchemaValidationResult;
import com.github.lisama569.dataqualitymonitorjava.engine.SchemaValidator;
import com.github.lisama569.dataqualitymonitorjava.exception.BatchRejectedException;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertRule;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertStatus;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertService;
import com.github.lisama569.dataqualitymonitorjava.store.BatchStore;
import com.github.lisama569.dataqualitymonitorjava.store.MetricsStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 服务门面，职责：
 * 1) 注册前校验契约/规则（非法规则拒绝，不静默跳过）；
 * 2) 提交前做字段与结构漂移检查，给出可区分的拒绝原因；
 * 3) 以 batchId 为幂等键：内容一致的重复提交直接返回既有结论（不重复累计指标），
 *    标识相同但内容哈希不同则明确拒绝（DUPLICATE_BATCH_CONFLICT），绝不覆盖；
 * 4) 评估结论沉淀为指标时序，并驱动告警状态流转。
 */
@Service
public class QualityMonitorService {

    private static final Logger log = LoggerFactory.getLogger(QualityMonitorService.class);

    private final ContractRegistry contractRegistry;
    private final SchemaValidator schemaValidator;
    private final QualityEvaluationEngine engine;
    private final BatchStore batchStore;
    private final MetricsStore metricsStore;
    private final AlertService alertService;
    private final TimeService timeService;

    public QualityMonitorService(ContractRegistry contractRegistry,
                                 SchemaValidator schemaValidator,
                                 QualityEvaluationEngine engine,
                                 BatchStore batchStore,
                                 MetricsStore metricsStore,
                                 AlertService alertService,
                                 TimeService timeService) {
        this.contractRegistry = contractRegistry;
        this.schemaValidator = schemaValidator;
        this.engine = engine;
        this.batchStore = batchStore;
        this.metricsStore = metricsStore;
        this.alertService = alertService;
        this.timeService = timeService;
    }

    public DataContract registerContract(String dataset, List<FieldSpec> fields, List<QualityRule> rules) {
        return contractRegistry.register(dataset,
                fields == null ? List.of() : fields,
                rules == null ? List.of() : rules);
    }

    public BatchResult submit(BatchSubmission submission) {
        validateSubmissionShape(submission);

        DataContract contract = contractRegistry.current(submission.dataset());
        if (contract == null) {
            throw new BatchRejectedException(RejectReason.UNKNOWN_DATASET,
                    "数据集未注册任何数据契约: " + submission.dataset());
        }

        String contentHash = hashContent(submission);

        // 幂等路径：同一 batchId 已存在结论
        var existing = batchStore.get(submission.dataset(), submission.batchId());
        if (existing.isPresent()) {
            BatchResult first = existing.get();
            if (!first.contentHash().equals(contentHash)) {
                throw new BatchRejectedException(RejectReason.DUPLICATE_BATCH_CONFLICT,
                        "batchId=" + submission.batchId() + " 已存在不同内容的批次: 既有哈希="
                                + first.contentHash() + "，本次哈希=" + contentHash + "；拒绝覆盖既有结论");
            }
            log.info("[重复提交] batchId={} 内容哈希一致，返回既有结论 status={}，不重复累计指标",
                    submission.batchId(), first.status());
            return first;
        }

        // 结构漂移检查（缺失必填字段 / 未知字段，拒绝原因可区分）。
        // 被拒绝批次未进入评估、不占位，调用方可修正内容后用同一 batchId 重试；
        // 一旦批次进入评估（PASSED/FAILED），不同内容的重提将按冲突拒绝。
        SchemaValidationResult schema = schemaValidator.validate(contract, submission.rows());
        if (!schema.compatible()) {
            String reason = !schema.unknownFields().isEmpty()
                    ? RejectReason.SCHEMA_DRIFT_UNKNOWN_FIELD.name()
                    : RejectReason.SCHEMA_DRIFT_MISSING_REQUIRED_FIELD.name();
            throw new BatchRejectedException(
                    !schema.unknownFields().isEmpty()
                            ? RejectReason.SCHEMA_DRIFT_UNKNOWN_FIELD
                            : RejectReason.SCHEMA_DRIFT_MISSING_REQUIRED_FIELD,
                    "批次 " + submission.batchId() + " 结构漂移被拒绝(" + reason + "): 未知字段="
                            + schema.unknownFields() + "，缺失必填字段=" + schema.missingRequiredFields());
        }

        BatchResult evaluated = engine.evaluate(submission, contract, contentHash, false);

        // 原子占位：并发同内容提交只有一个线程写入成功，其余线程走幂等返回
        var raced = batchStore.putIfAbsent(evaluated);
        if (raced.isPresent()) {
            BatchResult winner = raced.get();
            if (!winner.contentHash().equals(contentHash)) {
                throw new BatchRejectedException(RejectReason.DUPLICATE_BATCH_CONFLICT,
                        "并发提交检测到 batchId=" + submission.batchId() + " 内容不一致，拒绝覆盖: 既有哈希="
                                + winner.contentHash() + "，本次哈希=" + contentHash);
            }
            log.info("[并发重复] batchId={} 另一线程已完成评估，返回既有结论，指标不重复累计",
                    submission.batchId());
            return winner;
        }

        recordMetrics(evaluated, contract);
        alertService.evaluateBatch(evaluated.dataset(), evaluated.batchId(), timeService.nowMillis());
        return evaluated;
    }

    private void validateSubmissionShape(BatchSubmission submission) {
        if (submission == null) {
            throw new BatchRejectedException(RejectReason.MISSING_FIELD, "请求体为空");
        }
        if (submission.dataset() == null || submission.dataset().isBlank()) {
            throw new BatchRejectedException(RejectReason.MISSING_FIELD, "缺少必填字段: dataset");
        }
        if (submission.batchId() == null || submission.batchId().isBlank()) {
            throw new BatchRejectedException(RejectReason.MISSING_FIELD, "缺少必填字段: batchId");
        }
        if (submission.rows() == null) {
            throw new BatchRejectedException(RejectReason.MISSING_FIELD, "缺少必填字段: rows");
        }
        if (submission.rows().isEmpty()) {
            throw new BatchRejectedException(RejectReason.EMPTY_BATCH,
                    "批次 " + submission.batchId() + " 不包含任何记录");
        }
    }

    /** 将每条规则结论沉淀为对应指标时序；同一批次同一指标只记录一次。 */
    private void recordMetrics(BatchResult result, DataContract contract) {
        long now = timeService.nowMillis();
        for (RuleVerdict verdict : result.verdicts()) {
            MetricName metric = metricOf(verdict.ruleType());
            boolean fresh = metricsStore.recordIfAbsent(result.batchId(), metric, result.dataset(),
                    verdict.observed(), now);
            log.info("[指标沉淀] batchId={} metric={} value={} 新增={}",
                    result.batchId(), metric, String.format("%.4f", verdict.observed()), fresh);
        }
    }

    private MetricName metricOf(RuleType ruleType) {
        return switch (ruleType) {
            case COMPLETENESS -> MetricName.COMPLETENESS_RATIO;
            case UNIQUENESS -> MetricName.UNIQUENESS_RATIO;
            case VALUE_DOMAIN -> MetricName.VALUE_DOMAIN_RATIO;
            case FRESHNESS -> MetricName.FRESHNESS_AGE_SECONDS;
        };
    }

    public BatchResult getBatch(String dataset, String batchId) {
        return batchStore.get(dataset, batchId)
                .orElseThrow(() -> new BatchRejectedException(RejectReason.MISSING_FIELD,
                        "批次不存在: dataset=" + dataset + ", batchId=" + batchId));
    }

    public List<BatchResult> listBatches(String dataset) {
        return batchStore.findByDataset(dataset);
    }

    public AlertRule registerAlert(String alertId, String dataset, MetricName metric,
                                   String operator, double threshold) {
        if (metric == null) {
            throw new com.github.lisama569.dataqualitymonitorjava.exception.InvalidRuleException(
                    RejectReason.UNKNOWN_METRIC,
                    "告警引用了未知指标（合法值: " + List.of(MetricName.values()) + "）");
        }
        if (contractRegistry.current(dataset) == null) {
            throw new BatchRejectedException(RejectReason.UNKNOWN_DATASET,
                    "数据集未注册任何数据契约: " + dataset);
        }
        return alertService.register(alertId, dataset, metric, operator, threshold);
    }

    public List<AlertStatus> listAlerts(String dataset) {
        return alertService.list(dataset);
    }

    /**
     * 对提交内容做稳定哈希：行按顺序、每行按字段名排序后序列化，
     * 保证字节级一致的内容哈希相同，而键顺序不同或取值不同的内容哈希不同。
     */
    public String hashContent(BatchSubmission submission) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(submission.dataset().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(submission.eventTime()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (Map<String, Object> row : submission.rows()) {
                Map<String, Object> sorted = new TreeMap<>(row);
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '=');
                    digest.update(String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ';');
                }
                digest.update((byte) '|');
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
