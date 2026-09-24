package com.github.lisama569.dataqualitymonitorjava.web;

import com.github.lisama569.dataqualitymonitorjava.exception.BatchRejectedException;
import com.github.lisama569.dataqualitymonitorjava.exception.IllegalRuleException;
import com.github.lisama569.dataqualitymonitorjava.exception.UnknownMetricException;
import com.github.lisama569.dataqualitymonitorjava.model.Alert;
import com.github.lisama569.dataqualitymonitorjava.model.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.model.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.model.DataContract;
import com.github.lisama569.dataqualitymonitorjava.model.DataRow;
import com.github.lisama569.dataqualitymonitorjava.model.DataType;
import com.github.lisama569.dataqualitymonitorjava.model.FieldSchema;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.RuleType;
import com.github.lisama569.dataqualitymonitorjava.service.DataQualityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 本地数据质量服务 REST 接口。 */
@RestController
@RequestMapping("/api")
public class DataQualityController {

    private final DataQualityService service;

    public DataQualityController(DataQualityService service) {
        this.service = service;
    }

    @PostMapping("/contracts")
    public DataContract registerContract(@RequestBody ContractRequest request) {
        List<FieldSchema> fields = request.fields().stream()
                .map(f -> new FieldSchema(f.name(), parseType(f.dataType()), f.required()))
                .toList();
        int version = request.version() == null ? 1 : request.version();
        return service.registerContract(new DataContract(request.contractId(), version, fields, Instant.now()));
    }

    @PostMapping("/rules")
    public QualityRule registerRule(@RequestBody RuleRequest request) {
        RuleType type = RuleType.fromCode(request.type());
        QualityRule rule = new QualityRule(request.contractId(), request.ruleId(), 0, type,
                request.fieldName(), request.threshold(), request.allowedValues(),
                request.numericMin(), request.numericMax(), request.maxAgeSeconds(), Instant.now());
        return service.registerValidatedRule(rule);
    }

    @PostMapping("/batches")
    public BatchResult submitBatch(@RequestBody BatchRequest request) {
        Instant submittedAt = request.submittedAt() == null ? Instant.now() : request.submittedAt();
        List<DataRow> rows = request.rows().stream().map(DataRow::new).toList();
        return service.submit(new BatchSubmission(request.batchId(), request.contractId(), submittedAt, rows));
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<BatchResult> getBatch(@PathVariable String batchId) {
        return service.getBatch(batchId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/batches")
    public List<BatchResult> listBatches() {
        return service.listBatches();
    }

    @GetMapping("/alerts")
    public List<Alert> listAlerts() {
        return service.listAlerts();
    }

    /** 重置本地存储，仅供本地验证/测试使用。 */
    @PostMapping("/admin/reset")
    public Map<String, String> reset() {
        service.reset();
        return Map.of("result", "OK");
    }

    /** 拒绝原因（结构漂移、同 ID 冲突、空批次等）以 422 返回并分类列出。 */
    @ExceptionHandler(BatchRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(BatchRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "BATCH_REJECTED",
                "rejections", ex.getReasons().stream()
                        .map(r -> Map.of("reason", r.reason().name(), "detail", r.detail()))
                        .toList()));
    }

    /** 规则配置非法：400。 */
    @ExceptionHandler(IllegalRuleException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalRule(IllegalRuleException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "ILLEGAL_RULE",
                "ruleId", ex.getRuleId(),
                "violations", ex.getViolations()));
    }

    /** 未知指标：400，与非法规则区分。 */
    @ExceptionHandler(UnknownMetricException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownMetric(UnknownMetricException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "UNKNOWN_METRIC",
                "metric", ex.getUnknownCode()));
    }

    private static DataType parseType(String code) {
        return DataType.valueOf(code.trim().toUpperCase());
    }
}
