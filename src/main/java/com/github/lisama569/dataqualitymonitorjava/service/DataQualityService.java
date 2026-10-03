package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.exception.BatchRejectedException;
import com.github.lisama569.dataqualitymonitorjava.exception.IllegalRuleException;
import com.github.lisama569.dataqualitymonitorjava.model.Alert;
import com.github.lisama569.dataqualitymonitorjava.model.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.model.BatchStatus;
import com.github.lisama569.dataqualitymonitorjava.model.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.model.DataContract;
import com.github.lisama569.dataqualitymonitorjava.model.DataRow;
import com.github.lisama569.dataqualitymonitorjava.model.FieldSchema;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.model.ReplayReport;
import com.github.lisama569.dataqualitymonitorjava.model.RuleOutcome;
import com.github.lisama569.dataqualitymonitorjava.storage.LocalStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据质量门面服务。
 * 处理顺序保证结论可预期：契约与空批次校验 -&gt; 结构漂移 -&gt; 规则非法/未知指标，
 * 任一拒绝原因成立则批次以 REJECTED 落库并抛出异常，绝不进入评估、绝不累计指标；
 * 同一 batchId 的并发提交在分段锁内串行，重复内容返回既有结论，同 ID 不同内容被拒绝。
 */
@Service
public class DataQualityService {

    private static final Logger log = LoggerFactory.getLogger(DataQualityService.class);

    private final LocalStorage storage;
    private final ContentHasher contentHasher;
    private final RuleValidator ruleValidator;
    private final QualityEvaluator evaluator;
    private final AlertService alertService;
    private final ReplayService replayService;

    /** 每个 batchId 一把锁，既保证同批次并发提交串行，又不阻塞不同批次。 */
    private final ConcurrentHashMap<String, Object> batchLocks = new ConcurrentHashMap<>();

    public DataQualityService(LocalStorage storage, ContentHasher contentHasher,
                              RuleValidator ruleValidator, QualityEvaluator evaluator,
                              AlertService alertService, ReplayService replayService) {
        this.storage = storage;
        this.contentHasher = contentHasher;
        this.ruleValidator = ruleValidator;
        this.evaluator = evaluator;
        this.alertService = alertService;
        this.replayService = replayService;
    }

    /** 只读复盘：按指定过去判定时刻重算契约下批次的新鲜度结论，绝不回写任何状态。 */
    public ReplayReport replay(String contractId, Instant judgedAt) {
        ReplayReport report = replayService.replay(contractId, judgedAt);
        log.info("只读复盘 contract={} judgedAt={} 批次数={} 越界批次数={}",
                contractId, judgedAt, report.batchCount(), report.violatingBatchCount());
        return report;
    }

    public DataContract registerContract(DataContract contract) {
        storage.saveContract(contract);
        log.info("登记数据契约 id={} version={} 字段={}",
                contract.getContractId(), contract.getVersion(), contract.columnNames());
        return contract;
    }

    /** 登记/更新规则；相同 (contractId,ruleId) 再次登记自动产生新版本，历史版本保留。 */
    public QualityRule registerRule(QualityRule rule) {
        int nextVersion = storage.findLatestRule(rule.getRuleId())
                .map(r -> r.getVersion() + 1).orElse(1);
        QualityRule versioned = new QualityRule(rule.getContractId(), rule.getRuleId(), nextVersion,
                rule.getType(), rule.getFieldName(), rule.getThreshold(), rule.getAllowedValues(),
                rule.getNumericMin(), rule.getNumericMax(), rule.getMaxAgeSeconds(),
                rule.getUpdatedAt() == null ? Instant.now() : rule.getUpdatedAt());
        storage.saveRule(versioned);
        log.info("登记质量规则 contract={} id={} version={} type={} field={}",
                versioned.getContractId(), versioned.getRuleId(), versioned.getVersion(),
                versioned.getType(), versioned.getFieldName());
        return versioned;
    }

    /** 登记规则前做合法性校验（规则字段必须存在于契约中，参数必须完整、范围合法）。 */
    public QualityRule registerValidatedRule(QualityRule rule) {
        DataContract contract = storage.findContract(rule.getContractId())
                .orElseThrow(() -> new BatchRejectedException(List.of(
                        new BatchRejectedException.RejectReasonEntry(
                                RejectReason.CONTRACT_NOT_FOUND,
                                RejectReason.CONTRACT_NOT_FOUND.defaultMessage()
                                        + ": " + rule.getContractId()))));
        List<String> violations = ruleValidator.validate(rule, contract);
        if (!violations.isEmpty()) {
            throw new IllegalRuleException(rule.getRuleId(), violations);
        }
        return registerRule(rule);
    }

    public BatchResult submit(BatchSubmission submission) {
        return submit(submission, null);
    }

    /**
     * 提交批次并评估。
     *
     * @param judgedAt 判定时刻：新鲜度按“业务时点 -&gt; 判定时刻”的滞后判定。
     *                 传 null 时默认取系统当前时间；复盘/测试可显式指定，
     *                 相同（业务时点, 判定时刻）无论何时提交，结论与依据都完全一致。
     */
    public BatchResult submit(BatchSubmission submission, Instant judgedAt) {
        String batchId = submission.getBatchId();
        Object lock = batchLocks.computeIfAbsent(batchId, k -> new Object());
        synchronized (lock) {
            // 1. 并发/重复提交：锁内再次确认，确保只评估一次。
            Optional<BatchResult> existing = storage.findBatch(batchId);
            String hash = contentHasher.hash(submission);
            if (existing.isPresent()) {
                BatchResult prior = existing.get();
                if (prior.getContentHash().equals(hash)) {
                    log.info("批次 {} 为重复提交（内容哈希一致），返回既有结论 status={}，指标不重复累计",
                            batchId, prior.getStatus());
                    // 存储中的唯一结论保持不变；仅在本次响应上标记 duplicate，便于调用方识别。
                    return new BatchResult(prior.getBatchId(), prior.getContractId(), prior.getStatus(),
                            prior.getContentHash(), true, prior.getRejections(), prior.getOutcomes(),
                            prior.getRuleSnapshots(), prior.getEvaluatedAt(),
                            prior.getBusinessTime(), prior.getJudgedAt());
                }
                return reject(submission, hash, false, List.of(new BatchResult.Rejection(
                        RejectReason.DUPLICATE_ID_CONTENT_CONFLICT,
                        "既有内容哈希=" + prior.getContentHash() + "，本次内容哈希=" + hash)));
            }

            // 2. 前置校验，汇总全部拒绝原因（不静默跳过任何一类问题）。
            List<BatchResult.Rejection> rejections = preCheck(submission, hash);
            if (!rejections.isEmpty()) {
                return reject(submission, hash, false, rejections);
            }

            // 3. 评估。判定时刻显式指定优先，缺省取系统当前时间；业务时点取自批次自身。
            Instant effectiveJudgedAt = judgedAt != null ? judgedAt : Instant.now();
            Instant businessTime = submission.effectiveBusinessTime();
            DataContract contract = storage.findContract(submission.getContractId()).orElseThrow();
            List<QualityRule> rules = storage.listLatestRulesForContract(submission.getContractId());
            List<RuleOutcome> outcomes =
                    evaluator.evaluate(submission, contract, rules, effectiveJudgedAt);
            boolean allPassed = outcomes.stream().allMatch(RuleOutcome::isPassed);
            BatchStatus status = allPassed ? BatchStatus.PASSED : BatchStatus.FAILED;
            BatchResult result = new BatchResult(batchId, submission.getContractId(), status, hash,
                    false, List.of(), List.copyOf(outcomes), List.copyOf(rules), Instant.now(),
                    businessTime, effectiveJudgedAt);
            storage.saveBatch(result);

            log.info("批次 {} 评估完成 status={} 业务时点={} 判定时刻={} 命中规则数={}",
                    batchId, status, businessTime, effectiveJudgedAt, outcomes.size());
            for (RuleOutcome o : outcomes) {
                log.info("批次 {} 规则 {} v{} [{}] field={} passed={} measured={} expected={} 依据={}",
                        batchId, o.getRuleId(), o.getRuleVersion(), o.getRuleType(),
                        o.getFieldName(), o.isPassed(), o.getMeasured(), o.getExpected(), o.getBasis());
            }

            // 4. 驱动告警状态流转（仅对已评估批次）；按业务时点做迟到/回填保护。
            List<Alert> alerts = alertService.process(result, rules, businessTime);
            for (Alert a : alerts) {
                log.info("批次 {} 告警 rule={} state={} occurrence={} openCount={} 业务水位={}",
                        batchId, a.getRuleId(), a.getState(), a.getOccurrenceCount(),
                        a.getOpenCount(), a.getWatermarkBusinessTime());
            }
            return result;
        }
    }

    /** 前置校验：契约、空批次、结构漂移、规则非法/未知指标。 */
    private List<BatchResult.Rejection> preCheck(BatchSubmission submission, String hash) {
        List<BatchResult.Rejection> rejections = new ArrayList<>();

        Optional<DataContract> contractOpt = storage.findContract(submission.getContractId());
        if (contractOpt.isEmpty()) {
            rejections.add(new BatchResult.Rejection(RejectReason.CONTRACT_NOT_FOUND,
                    RejectReason.CONTRACT_NOT_FOUND.defaultMessage() + ": " + submission.getContractId()));
            return rejections;
        }
        DataContract contract = contractOpt.get();

        if (submission.getRows() == null || submission.getRows().isEmpty()) {
            rejections.add(new BatchResult.Rejection(RejectReason.EMPTY_BATCH,
                    RejectReason.EMPTY_BATCH.defaultMessage()));
            return rejections;
        }

        // 结构漂移：列集合必须与契约严格一致（缺失列与多余列分别报告）。
        Set<String> contractColumns = new HashSet<>(contract.columnNames());
        Set<String> actualColumns = new HashSet<>();
        for (DataRow row : submission.getRows()) {
            actualColumns.addAll(row.values().keySet());
        }
        List<String> missing = contractColumns.stream()
                .filter(c -> !actualColumns.contains(c)).sorted().toList();
        List<String> unexpected = actualColumns.stream()
                .filter(c -> !contractColumns.contains(c)).sorted().toList();
        if (!missing.isEmpty()) {
            rejections.add(new BatchResult.Rejection(RejectReason.SCHEMA_MISSING_COLUMN,
                    "批次缺少契约声明的字段: " + missing));
        }
        if (!unexpected.isEmpty()) {
            rejections.add(new BatchResult.Rejection(RejectReason.SCHEMA_UNEXPECTED_COLUMN,
                    "批次出现契约未声明的字段: " + unexpected));
        }

        // 当前生效规则逐条校验；非法规则与未知指标必须显式拒绝整批。
        for (QualityRule rule : storage.listLatestRulesForContract(submission.getContractId())) {
            List<String> violations = ruleValidator.validate(rule, contract);
            if (!violations.isEmpty()) {
                rejections.add(new BatchResult.Rejection(RejectReason.ILLEGAL_RULE,
                        "规则 " + rule.getRuleId() + " v" + rule.getVersion() + " 非法: " + violations));
            }
        }
        return rejections;
    }

    private BatchResult reject(BatchSubmission submission, String hash, boolean duplicate,
                               List<BatchResult.Rejection> rejections) {
        BatchResult result = new BatchResult(submission.getBatchId(), submission.getContractId(),
                BatchStatus.REJECTED, hash, duplicate, List.copyOf(rejections),
                List.of(), List.of(), Instant.now());
        storage.saveBatch(result);
        for (BatchResult.Rejection r : rejections) {
            log.warn("批次 {} 被拒绝 reason={} detail={}",
                    submission.getBatchId(), r.reason(), r.detail());
        }
        throw new BatchRejectedException(rejections.stream()
                .map(r -> new BatchRejectedException.RejectReasonEntry(r.reason(), r.detail()))
                .toList());
    }

    public Optional<BatchResult> getBatch(String batchId) {
        return storage.findBatch(batchId);
    }

    public List<BatchResult> listBatches() {
        return storage.listBatches();
    }

    public List<Alert> listAlerts() {
        return alertService.list();
    }

    /** 清空本地存储（契约/规则/批次/告警），供本地验证与测试重置。 */
    public void reset() {
        storage.reset();
        log.info("本地数据质量存储已重置");
    }

    /** 测试辅助：根据字段定义构造契约。 */
    public static DataContract contract(String id, int version, List<FieldSchema> fields) {
        return new DataContract(id, version, fields, Instant.now());
    }
}
