package com.github.lisama569.dataqualitymonitorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchStatus;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.contract.ContractRegistry;
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

/**
 * 契约演进（规则/字段/阈值变更）后：
 * - 历史批次保留评估时的契约版本号与判定快照，结论不漂移；
 * - 新批次按新版本契约评估；历史版本契约仍可取回复核。
 */
class HistoryReproducibilityTest {

    private static final Logger log = LoggerFactory.getLogger(HistoryReproducibilityTest.class);

    @Test
    void contractEvolutionDoesNotRewriteHistory() {
        TestFixture.Harness h = TestFixture.newService("2026-09-24T10:00:00Z");
        long eventTime = java.time.Instant.parse("2026-09-24T09:00:00Z").toEpochMilli();

        // v1：完整性阈值 0.5，0.5 的批次可通过
        h.service().registerContract("ds",
                List.of(new FieldSpec("id", FieldType.STRING, true)),
                List.of(new QualityRule("r-complete", RuleType.COMPLETENESS, "id", Map.of("min", 0.5))));
        BatchResult old = h.service().submit(new BatchSubmission("ds", "old-batch",
                List.of(Map.of("id", "x"), Map.of("id", "")), eventTime));
        assertEquals(BatchStatus.PASSED, old.status());
        assertEquals(1, old.contractVersion());

        // v2：阈值收紧到 1.0，并新增必填字段
        h.service().registerContract("ds",
                List.of(new FieldSpec("id", FieldType.STRING, true),
                        new FieldSpec("region", FieldType.STRING, true)),
                List.of(new QualityRule("r-complete", RuleType.COMPLETENESS, "id", Map.of("min", 1.0))));

        // 历史结论保持 v1 的 PASSED，不被 v2 重写
        BatchResult stored = h.service().getBatch("ds", "old-batch");
        assertEquals(old, stored);
        assertEquals(BatchStatus.PASSED, stored.status());
        assertEquals(1, stored.contractVersion());
        assertEquals(0.5, stored.verdicts().get(0).observed(), 1e-9);

        // 新批次按 v2 评估：缺少 region -> 漂移拒绝；补齐后按 v2 规则 FAILED
        BatchResult fresh = h.service().submit(new BatchSubmission("ds", "new-batch",
                List.of(Map.of("id", "x", "region", "cn"),
                        Map.of("id", "", "region", "cn")), eventTime));
        assertEquals(BatchStatus.FAILED, fresh.status());
        assertEquals(2, fresh.contractVersion());
        assertNotEquals(stored.status(), fresh.status());

        // v1 契约仍可从注册表取回用于复核
        ContractRegistry registry = h.contracts();
        assertEquals(0.5, ((Number) registry.version("ds", 1).rules().get(0).params().get("min")).doubleValue());
        assertEquals(1.0, ((Number) registry.version("ds", 2).rules().get(0).params().get("min")).doubleValue());
        log.info("[测试断言] old-batch 保持 v1={}，new-batch 按 v2={}，两版契约均可取回复现",
                stored.status(), fresh.status());
    }
}
