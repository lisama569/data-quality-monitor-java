package com.github.lisama569.dataqualitymonitorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.common.MetricName;
import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldType;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;
import com.github.lisama569.dataqualitymonitorjava.exception.BatchRejectedException;
import com.github.lisama569.dataqualitymonitorjava.exception.InvalidRuleException;
import com.github.lisama569.dataqualitymonitorjava.tests.support.TestFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拒绝原因必须可区分：
 * 结构漂移（未知字段/缺失必填字段）、非法规则（未知字段引用/阈值越界/枚举非法）、
 * 未知指标、缺失入参字段、未知数据集、空批次，均不得静默跳过。
 */
class RejectionClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(RejectionClassificationTest.class);
    private static final String NOW = "2026-09-24T10:00:00Z";
    private static final long EVENT_TIME = java.time.Instant.parse(NOW).toEpochMilli();

    private TestFixture.Harness harness() {
        TestFixture.Harness h = TestFixture.newService(NOW);
        h.service().registerContract("orders",
                List.of(new FieldSpec("id", FieldType.STRING, true),
                        new FieldSpec("amount", FieldType.DOUBLE, false)),
                List.of());
        return h;
    }

    private BatchRejectedException reject(TestFixture.Harness h, BatchSubmission submission) {
        return assertThrows(BatchRejectedException.class, () -> h.service().submit(submission));
    }

    @Test
    void unknownFieldIsSchemaDrift() {
        var h = harness();
        var ex = reject(h, new BatchSubmission("orders", "s1",
                List.of(Map.of("id", "o1", "ghost", "x")), EVENT_TIME));
        assertEquals(RejectReason.SCHEMA_DRIFT_UNKNOWN_FIELD, ex.reason());
        assertTrue(ex.getMessage().contains("ghost"));
        log.info("[测试断言] batchId=s1 拒绝原因={} message={}", ex.reason(), ex.getMessage());
    }

    @Test
    void missingRequiredFieldIsSchemaDrift() {
        var h = harness();
        var ex = reject(h, new BatchSubmission("orders", "s2",
                List.of(Map.of("amount", 1.0)), EVENT_TIME));
        assertEquals(RejectReason.SCHEMA_DRIFT_MISSING_REQUIRED_FIELD, ex.reason());
        assertTrue(ex.getMessage().contains("id"));
        log.info("[测试断言] batchId=s2 拒绝原因={} message={}", ex.reason(), ex.getMessage());
    }

    @Test
    void missingRequestFieldsAreRejected() {
        var h = harness();
        assertEquals(RejectReason.MISSING_FIELD,
                reject(h, new BatchSubmission(null, "s3", List.of(Map.of("id", "o1")), EVENT_TIME)).reason());
        assertEquals(RejectReason.MISSING_FIELD,
                reject(h, new BatchSubmission("orders", " ", List.of(Map.of("id", "o1")), EVENT_TIME)).reason());
        assertEquals(RejectReason.MISSING_FIELD,
                reject(h, new BatchSubmission("orders", "s4", null, EVENT_TIME)).reason());
        assertEquals(RejectReason.EMPTY_BATCH,
                reject(h, new BatchSubmission("orders", "s5", List.of(), EVENT_TIME)).reason());
        log.info("[测试断言] dataset/batchId/rows 缺失与空批次分别归类为 MISSING_FIELD / EMPTY_BATCH");
    }

    @Test
    void unknownDatasetIsRejected() {
        var h = TestFixture.newService(NOW);
        var ex = reject(h, new BatchSubmission("ghost-dataset", "s6",
                List.of(Map.of("id", "o1")), EVENT_TIME));
        assertEquals(RejectReason.UNKNOWN_DATASET, ex.reason());
        log.info("[测试断言] batchId=s6 拒绝原因={}", ex.reason());
    }

    @Test
    void ruleReferencingUnknownFieldIsInvalid() {
        var h = TestFixture.newService(NOW);
        InvalidRuleException ex = assertThrows(InvalidRuleException.class,
                () -> h.service().registerContract("bad1",
                        List.of(new FieldSpec("id", FieldType.STRING, true)),
                        List.of(new QualityRule("bad-rule", RuleType.COMPLETENESS, "nope",
                                Map.of("min", 0.9)))));
        assertEquals(RejectReason.INVALID_RULE_CONFIG, ex.reason());
        assertTrue(ex.getMessage().contains("nope"));
        log.info("[测试断言] 规则引用未知字段被拒绝: {}", ex.getMessage());
    }

    @Test
    void illegalThresholdsAreRejectedNotSilentlySkipped() {
        var h = TestFixture.newService(NOW);
        InvalidRuleException minOutOfRange = assertThrows(InvalidRuleException.class,
                () -> h.service().registerContract("bad2",
                        List.of(new FieldSpec("id", FieldType.STRING, true)),
                        List.of(new QualityRule("r", RuleType.COMPLETENESS, "id", Map.of("min", 1.5)))));
        assertEquals(RejectReason.INVALID_RULE_CONFIG, minOutOfRange.reason());

        InvalidRuleException noDomain = assertThrows(InvalidRuleException.class,
                () -> h.service().registerContract("bad3",
                        List.of(new FieldSpec("id", FieldType.STRING, true)),
                        List.of(new QualityRule("r2", RuleType.VALUE_DOMAIN, "id", Map.of()))));
        assertEquals(RejectReason.INVALID_RULE_CONFIG, noDomain.reason());

        InvalidRuleException badFreshness = assertThrows(InvalidRuleException.class,
                () -> h.service().registerContract("bad4",
                        List.of(new FieldSpec("id", FieldType.STRING, true)),
                        List.of(new QualityRule("r3", RuleType.FRESHNESS, null,
                                Map.of("maxAgeSeconds", -1)))));
        assertEquals(RejectReason.INVALID_RULE_CONFIG, badFreshness.reason());
        log.info("[测试断言] 阈值越界/取值域缺参/新鲜度负值均归类 INVALID_RULE_CONFIG，无一静默跳过");
    }

    @Test
    void duplicateRuleIdIsRejected() {
        var h = TestFixture.newService(NOW);
        InvalidRuleException ex = assertThrows(InvalidRuleException.class,
                () -> h.service().registerContract("bad5",
                        List.of(new FieldSpec("id", FieldType.STRING, true)),
                        List.of(
                                new QualityRule("dup", RuleType.COMPLETENESS, "id", Map.of("min", 1.0)),
                                new QualityRule("dup", RuleType.COMPLETENESS, "id", Map.of("min", 0.5)))));
        assertEquals(RejectReason.INVALID_RULE_CONFIG, ex.reason());
        assertTrue(ex.getMessage().contains("dup"));
        log.info("[测试断言] 规则编号重复被拒绝: {}", ex.getMessage());
    }

    @Test
    void unknownMetricIsRejected() {
        var h = harness();
        InvalidRuleException ex = assertThrows(InvalidRuleException.class,
                () -> h.service().registerAlert("a1", "orders", null, "<", 0.5));
        assertEquals(RejectReason.UNKNOWN_METRIC, ex.reason());
        log.info("[测试断言] 告警引用未知指标归类 UNKNOWN_METRIC: {}", ex.getMessage());
        // 合法指标可以注册，证明枚举值本身可正常解析
        var rule = h.service().registerAlert("a2", "orders", MetricName.COMPLETENESS_RATIO, "<", 0.9);
        assertEquals(1, rule.version());
    }
}
