package com.github.lisama569.dataqualitymonitorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchStatus;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldType;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;
import com.github.lisama569.dataqualitymonitorjava.tests.support.TestFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 四类质量规则的通过/失败判定，失败原因按规则类型可区分，结论确定性可复现。 */
class QualityRuleEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(QualityRuleEvaluationTest.class);
    private static final String NOW = "2026-09-24T10:00:00Z";

    private TestFixture.Harness harness() {
        TestFixture.Harness h = TestFixture.newService(NOW);
        h.service().registerContract("orders",
                List.of(
                        new FieldSpec("id", FieldType.STRING, true),
                        new FieldSpec("user_id", FieldType.STRING, true),
                        new FieldSpec("amount", FieldType.DOUBLE, false)),
                List.of(
                        new QualityRule("r-id-complete", RuleType.COMPLETENESS, "id", Map.of("min", 1.0)),
                        new QualityRule("r-user-unique", RuleType.UNIQUENESS, "user_id", Map.of()),
                        new QualityRule("r-amount-domain", RuleType.VALUE_DOMAIN, "amount",
                                Map.of("min", 0.0, "max", 100.0))));
        return h;
    }

    private BatchSubmission batch(String batchId, long eventTime, Map<String, Object>... rows) {
        return new BatchSubmission("orders", batchId, List.of(rows), eventTime);
    }

    @Test
    void allRulesPass() {
        var h = harness();
        long eventTime = java.time.Instant.parse(NOW).toEpochMilli() - 60_000;
        BatchResult result = h.service().submit(batch("b-pass", eventTime,
                Map.of("id", "o1", "user_id", "u1", "amount", 10.0),
                Map.of("id", "o2", "user_id", "u2", "amount", 99.0)));
        assertEquals(BatchStatus.PASSED, result.status());
        assertEquals(3, result.verdicts().size());
        log.info("[测试断言] batchId={} 全部规则通过 PASSED", result.batchId());
    }

    @Test
    void completenessFailureIsClassified() {
        var h = harness();
        BatchResult result = h.service().submit(batch("b-completeness",
                java.time.Instant.parse(NOW).toEpochMilli(),
                Map.of("id", "o1", "user_id", "u1", "amount", 10.0),
                Map.of("id", "", "user_id", "u2", "amount", 20.0)));
        assertEquals(BatchStatus.FAILED, result.status());
        var verdict = result.verdicts().get(0);
        assertFalse(verdict.passed());
        assertEquals(RuleType.COMPLETENESS, verdict.ruleType());
        assertEquals(0.5, verdict.observed(), 1e-9);
        assertTrue(verdict.detail().contains("COMPLETENESS_BELOW_THRESHOLD"),
                "失败原因必须可区分: " + verdict.detail());
        log.info("[测试断言] batchId={} 命中规则={} 判定依据={}", result.batchId(), verdict.ruleId(),
                verdict.detail());
    }

    @Test
    void uniquenessFailureIsClassified() {
        var h = harness();
        BatchResult result = h.service().submit(batch("b-uniqueness",
                java.time.Instant.parse(NOW).toEpochMilli(),
                Map.of("id", "o1", "user_id", "u1", "amount", 10.0),
                Map.of("id", "o2", "user_id", "u1", "amount", 20.0)));
        var verdict = result.verdicts().get(1);
        assertFalse(verdict.passed());
        assertEquals(RuleType.UNIQUENESS, verdict.ruleType());
        assertTrue(verdict.detail().contains("UNIQUENESS_VIOLATION"), verdict.detail());
        assertTrue(verdict.detail().contains("u1x2"), "判定依据需列出重复键: " + verdict.detail());
        log.info("[测试断言] batchId={} 命中规则={} 判定依据={}", result.batchId(), verdict.ruleId(),
                verdict.detail());
    }

    @Test
    void valueDomainFailureIsClassified() {
        var h = harness();
        BatchResult result = h.service().submit(batch("b-domain",
                java.time.Instant.parse(NOW).toEpochMilli(),
                Map.of("id", "o1", "user_id", "u1", "amount", 10.0),
                Map.of("id", "o2", "user_id", "u2", "amount", 250.0)));
        var verdict = result.verdicts().get(2);
        assertFalse(verdict.passed());
        assertEquals(RuleType.VALUE_DOMAIN, verdict.ruleType());
        assertEquals(0.5, verdict.observed(), 1e-9);
        assertTrue(verdict.detail().contains("VALUE_OUT_OF_DOMAIN"), verdict.detail());
        assertTrue(verdict.detail().contains("250"), verdict.detail());
        log.info("[测试断言] batchId={} 命中规则={} 判定依据={}", result.batchId(), verdict.ruleId(),
                verdict.detail());
    }

    @Test
    void freshnessRuleRequiresContractRuleAndFixedClock() {
        var h = TestFixture.newService(NOW);
        h.service().registerContract("events",
                List.of(new FieldSpec("id", FieldType.STRING, true)),
                List.of(new QualityRule("r-fresh", RuleType.FRESHNESS, null,
                        Map.of("maxAgeSeconds", 100.0))));
        long staleEventTime = java.time.Instant.parse(NOW).toEpochMilli() - 200_000;
        BatchResult stale = h.service().submit(new BatchSubmission("events", "b-stale",
                List.of(Map.of("id", "e1")), staleEventTime));
        var verdict = stale.verdicts().get(0);
        assertFalse(verdict.passed());
        assertEquals(RuleType.FRESHNESS, verdict.ruleType());
        assertEquals(200.0, verdict.observed(), 1e-9);
        assertTrue(verdict.detail().contains("FRESHNESS_STALE"), verdict.detail());
        log.info("[测试断言] batchId={} 命中规则={} 判定依据={}", stale.batchId(), verdict.ruleId(),
                verdict.detail());

        long freshEventTime = java.time.Instant.parse(NOW).toEpochMilli() - 50_000;
        BatchResult fresh = h.service().submit(new BatchSubmission("events", "b-fresh",
                List.of(Map.of("id", "e2")), freshEventTime));
        assertEquals(BatchStatus.PASSED, fresh.status());
    }
}
