package com.github.lisama569.dataqualitymonitorjava.tests.support;

import com.github.lisama569.dataqualitymonitorjava.common.TimeService;
import com.github.lisama569.dataqualitymonitorjava.contract.ContractRegistry;
import com.github.lisama569.dataqualitymonitorjava.engine.QualityEvaluationEngine;
import com.github.lisama569.dataqualitymonitorjava.engine.SchemaValidator;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertEvaluator;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertService;
import com.github.lisama569.dataqualitymonitorjava.rule.CompletenessEvaluator;
import com.github.lisama569.dataqualitymonitorjava.rule.FreshnessEvaluator;
import com.github.lisama569.dataqualitymonitorjava.rule.RuleEvaluatorRegistry;
import com.github.lisama569.dataqualitymonitorjava.rule.RuleValidator;
import com.github.lisama569.dataqualitymonitorjava.rule.UniquenessEvaluator;
import com.github.lisama569.dataqualitymonitorjava.rule.ValueDomainEvaluator;
import com.github.lisama569.dataqualitymonitorjava.service.QualityMonitorService;
import com.github.lisama569.dataqualitymonitorjava.store.BatchStore;
import com.github.lisama569.dataqualitymonitorjava.store.MetricsStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** 纯手工装配（不启动 Spring），固定时钟保证新鲜度/告警时序结论可复现。 */
public final class TestFixture {

    private TestFixture() {
    }

    public record Harness(QualityMonitorService service,
                          ContractRegistry contracts,
                          MetricsStore metricsStore,
                          AlertService alertService,
                          BatchStore batchStore) {
    }

    public static Harness newService(String fixedInstant) {
        Clock clock = Clock.fixed(Instant.parse(fixedInstant), ZoneOffset.UTC);
        RuleEvaluatorRegistry registry = new RuleEvaluatorRegistry(List.of(
                new CompletenessEvaluator(),
                new UniquenessEvaluator(),
                new ValueDomainEvaluator(),
                new FreshnessEvaluator()));
        ContractRegistry contractRegistry = new ContractRegistry(new RuleValidator());
        SchemaValidator schemaValidator = new SchemaValidator();
        QualityEvaluationEngine engine = new QualityEvaluationEngine(registry, clock);
        BatchStore batchStore = new BatchStore();
        MetricsStore metricsStore = new MetricsStore();
        AlertService alertService = new AlertService(metricsStore, new AlertEvaluator());
        TimeService timeService = new TimeService(clock);
        QualityMonitorService service = new QualityMonitorService(
                contractRegistry, schemaValidator, engine, batchStore,
                metricsStore, alertService, timeService);
        return new Harness(service, contractRegistry, metricsStore, alertService, batchStore);
    }
}
