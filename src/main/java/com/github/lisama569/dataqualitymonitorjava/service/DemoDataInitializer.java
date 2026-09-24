package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.common.MetricName;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldType;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时写入示例数据契约与告警规则，便于本地手工验证：
 * dataset=orders，字段 id(必填,STRING)/user_id(必填,STRING)/amount(DOUBLE)/created_at(TIMESTAMP)。
 */
@Component
public class DemoDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final QualityMonitorService service;

    public DemoDataInitializer(QualityMonitorService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) {
        service.registerContract("orders",
                List.of(
                        new FieldSpec("id", FieldType.STRING, true),
                        new FieldSpec("user_id", FieldType.STRING, true),
                        new FieldSpec("amount", FieldType.DOUBLE, false),
                        new FieldSpec("created_at", FieldType.TIMESTAMP, false)),
                List.of(
                        new QualityRule("r-id-complete", RuleType.COMPLETENESS, "id", Map.of("min", 1.0)),
                        new QualityRule("r-user-unique", RuleType.UNIQUENESS, "id", Map.of()),
                        new QualityRule("r-amount-domain", RuleType.VALUE_DOMAIN, "amount",
                                Map.of("min", 0.0, "max", 100000.0)),
                        new QualityRule("r-freshness", RuleType.FRESHNESS, null,
                                Map.of("maxAgeSeconds", 86400.0))));
        service.registerAlert("a-completeness", "orders", MetricName.COMPLETENESS_RATIO, "<", 0.95);
        service.registerAlert("a-freshness", "orders", MetricName.FRESHNESS_AGE_SECONDS, ">", 86400.0);
        log.info("[演示数据] 已注册数据集 orders 的契约(v1)与 2 条告警规则");
    }
}
