package com.github.lisama569.dataqualitymonitorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchStatus;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.common.MetricName;
import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldType;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;
import com.github.lisama569.dataqualitymonitorjava.exception.BatchRejectedException;
import com.github.lisama569.dataqualitymonitorjava.tests.support.TestFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同一批次重复/并发提交：
 * - 内容一致：返回同一既有结论，指标只累计一次；
 * - 同标识不同内容：明确拒绝（409 语义），不覆盖既有结果；
 * - 并发：只有一个提交真正完成评估。
 */
class BatchIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(BatchIdempotencyTest.class);
    private static final String NOW = "2026-09-24T10:00:00Z";

    private TestFixture.Harness harness() {
        TestFixture.Harness h = TestFixture.newService(NOW);
        h.service().registerContract("orders",
                List.of(new FieldSpec("id", FieldType.STRING, true),
                        new FieldSpec("user_id", FieldType.STRING, true)),
                List.of(new QualityRule("r-id-complete", RuleType.COMPLETENESS, "id", Map.of("min", 1.0)),
                        new QualityRule("r-user-unique", RuleType.UNIQUENESS, "user_id", Map.of())));
        return h;
    }

    private BatchSubmission submission(String batchId, String userId) {
        return new BatchSubmission("orders", batchId,
                List.of(Map.of("id", "o1", "user_id", userId)),
                java.time.Instant.parse(NOW).toEpochMilli());
    }

    @Test
    void exactDuplicateReturnsSameResultAndMetricsRecordedOnce() {
        var h = harness();
        BatchResult first = h.service().submit(submission("dup-1", "u1"));
        BatchResult second = h.service().submit(submission("dup-1", "u1"));
        BatchResult third = h.service().submit(submission("dup-1", "u1"));

        assertEquals(BatchStatus.PASSED, first.status());
        assertEquals(first, second, "重复提交必须返回同一结论对象，结论不得漂移");
        assertEquals(first, third);
        assertEquals(1, h.metricsStore().series("orders", MetricName.COMPLETENESS_RATIO).size(),
                "重复导入不得重复累计指标");
        assertEquals(1, h.metricsStore().series("orders", MetricName.UNIQUENESS_RATIO).size());
        assertEquals(1, h.batchStore().findByDataset("orders").size());
        log.info("[测试断言] batchId=dup-1 连续 3 次提交内容一致，结论相同，指标样本数=1");
    }

    @Test
    void sameBatchIdDifferentContentIsRejectedNotOverwritten() {
        var h = harness();
        BatchResult first = h.service().submit(submission("conflict-1", "u1"));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> h.service().submit(submission("conflict-1", "u2")));
        assertEquals(RejectReason.DUPLICATE_BATCH_CONFLICT, ex.reason());
        assertTrue(ex.getMessage().contains("拒绝覆盖既有结论"));
        // 既有结论保持不变
        BatchResult stored = h.service().getBatch("orders", "conflict-1");
        assertEquals(first, stored);
        assertEquals(first.contentHash(), stored.contentHash());
        log.info("[测试断言] batchId=conflict-1 同标识不同内容被明确拒绝 reason={}，既有哈希保持 {}",
                ex.reason(), stored.contentHash());
    }

    @Test
    void concurrentIdenticalSubmissionsEvaluateExactlyOnce() throws Exception {
        var h = harness();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<BatchResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> h.service().submit(submission("race-1", "u1")));
        }
        List<Future<BatchResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        BatchResult expected = futures.get(0).get();
        for (Future<BatchResult> future : futures) {
            assertEquals(expected, future.get(), "并发提交必须收敛到同一结论");
        }
        assertEquals(1, h.batchStore().findByDataset("orders").size());
        assertEquals(1, h.metricsStore().series("orders", MetricName.COMPLETENESS_RATIO).size());
        log.info("[测试断言] batchId=race-1 {} 个并发相同提交，仅评估 1 次，指标样本数=1", threads);
    }

    @Test
    void concurrentDifferentContentSameBatchIdOnlyOneWins() throws Exception {
        var h = harness();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String userId = "u" + i;
            tasks.add(() -> {
                h.service().submit(submission("race-2", userId));
                return null;
            });
        }
        List<Future<Object>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        int conflicts = 0;
        int successes = 0;
        for (Future<?> future : futures) {
            try {
                future.get();
                successes++;
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof BatchRejectedException bre
                        && bre.reason() == RejectReason.DUPLICATE_BATCH_CONFLICT) {
                    conflicts++;
                } else {
                    throw e;
                }
            }
        }
        assertEquals(1, successes, "同一 batchId 仅允许一个内容成功占位");
        assertEquals(threads - 1, conflicts);
        assertEquals(1, h.batchStore().findByDataset("orders").size());
        log.info("[测试断言] batchId=race-2 {} 个并发不同内容提交：1 个成功，{} 个冲突拒绝，无覆盖",
                threads, conflicts);
    }

    @Test
    void rejectedBySchemaCanBeResubmittedWithSameBatchId() {
        var h = harness();
        var drift = new BatchSubmission("orders", "retry-1",
                List.of(Map.of("id", "o1", "user_id", "u1", "extra", "?")),
                java.time.Instant.parse(NOW).toEpochMilli());
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> h.service().submit(drift));
        assertEquals(RejectReason.SCHEMA_DRIFT_UNKNOWN_FIELD, ex.reason());
        // 修正内容后同 batchId 重试成功，说明被拒绝批次没有占据幂等键
        BatchResult ok = h.service().submit(submission("retry-1", "u1"));
        assertEquals(BatchStatus.PASSED, ok.status());
        log.info("[测试断言] batchId=retry-1 漂移拒绝后修正内容重试成功");
    }
}
