package com.github.lisama569.dataqualitymonitorjava.web;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.common.MetricName;
import com.github.lisama569.dataqualitymonitorjava.contract.DataContract;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertRule;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertStatus;
import com.github.lisama569.dataqualitymonitorjava.service.QualityMonitorService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本地 HTTP 接口，便于手工验证批次提交、结论查询与告警状态。 */
@RestController
@RequestMapping("/api")
public class QualityMonitorController {

    private final QualityMonitorService service;

    public QualityMonitorController(QualityMonitorService service) {
        this.service = service;
    }

    public record ContractRequest(String dataset, List<FieldSpec> fields, List<QualityRule> rules) {
    }

    public record AlertRequest(String alertId, String dataset, MetricName metric, String operator, double threshold) {
    }

    @PostMapping("/contracts")
    public DataContract registerContract(@RequestBody ContractRequest request) {
        return service.registerContract(request.dataset(), request.fields(), request.rules());
    }

    @PostMapping("/batches")
    public BatchResult submit(@RequestBody BatchSubmission submission) {
        return service.submit(submission);
    }

    @GetMapping("/datasets/{dataset}/batches/{batchId}")
    public BatchResult getBatch(@PathVariable String dataset, @PathVariable String batchId) {
        return service.getBatch(dataset, batchId);
    }

    @GetMapping("/datasets/{dataset}/batches")
    public List<BatchResult> listBatches(@PathVariable String dataset) {
        return service.listBatches(dataset);
    }

    @PostMapping("/alerts")
    public AlertRule registerAlert(@RequestBody AlertRequest request) {
        return service.registerAlert(request.alertId(), request.dataset(), request.metric(),
                request.operator(), request.threshold());
    }

    @GetMapping("/datasets/{dataset}/alerts")
    public List<AlertStatus> listAlerts(@PathVariable String dataset) {
        return service.listAlerts(dataset);
    }
}
