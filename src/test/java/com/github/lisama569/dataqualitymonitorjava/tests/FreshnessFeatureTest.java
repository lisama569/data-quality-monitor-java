package com.github.lisama569.dataqualitymonitorjava.tests;

import com.github.lisama569.dataqualitymonitorjava.model.Alert;
import com.github.lisama569.dataqualitymonitorjava.model.AlertState;
import com.github.lisama569.dataqualitymonitorjava.model.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.model.BatchStatus;
import com.github.lisama569.dataqualitymonitorjava.model.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.model.DataContract;
import com.github.lisama569.dataqualitymonitorjava.model.DataRow;
import com.github.lisama569.dataqualitymonitorjava.model.DataType;
import com.github.lisama569.dataqualitymonitorjava.model.FieldSchema;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.ReplayBatchView;
import com.github.lisama569.dataqualitymonitorjava.model.ReplayReport;
import com.github.lisama569.dataqualitymonitorjava.model.RuleOutcome;
import com.github.lisama569.dataqualitymonitorjava.model.RuleType;
import com.github.lisama569.dataqualitymonitorjava.service.AlertService;
import com.github.lisama569.dataqualitymonitorjava.service.ContentHasher;
import com.github.lisama569.dataqualitymonitorjava.service.DataQualityService;
import com.github.lisama569.dataqualitymonitorjava.service.QualityEvaluator;
import com.github.lisama569.dataqualitymonitorjava.service.ReplayService;
import com.github.lisama569.dataqualitymonitorjava.service.RuleValidator;
import com.github.lisama569.dataqualitymonitorjava.storage.LocalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务时点新鲜度、迟到/回填保护与只读复盘的行为测试。
 * 所有新鲜度结论均通过显式判定时刻获得，保证与运行时刻无关、可复现。
 */
class FreshnessFeatureTest {

    private static final Logger log = LoggerFactory.getLogger(FreshnessFeatureTest.class);

    private static final String CONTRACT = "events-contract";
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final long MAX_AGE = 3600L;

    private LocalStorage storage;
    private DataQualityService service;

    @BeforeEach
    void setUp() {
        storage = new LocalStorage();
        QualityEvaluator evaluator = new QualityEvaluator();
        service = new DataQualityService(storage, new ContentHasher(), new RuleValidator(),
                evaluator, new AlertService(storage), new ReplayService(storage, evaluator));
        service.registerContract(new DataContract(CONTRACT, 1, List.of(
                new FieldSchema("id", DataType.INTEGER, true),
                new FieldSchema("payload", DataType.STRING, false)
        ), T0));
        service.registerValidatedRule(new QualityRule(CONTRACT, "r-fresh", 0,
                RuleType.FRESHNESS, null, null, null, null, null, MAX_AGE, T0));
    }

    private DataRow row(String id) {
        Map<String, String> m = new HashMap<>();
        m.put("id", id);
        m.put("payload", "x");
        return new DataRow(m);
    }

    /** 显式业务时点 + 到达时间（提交时间）的批次。 */
    private BatchSubmission batch(String batchId, Instant businessTime, Instant arrivedAt) {
        return new BatchSubmission(batchId, CONTRACT, arrivedAt, businessTime, List.of(row("1")));
    }

    private RuleOutcome freshnessOf(BatchResult r) {
        return r.getOutcomes().stream()
                .filter(o -> o.getRuleType() == RuleType.FRESHNESS).findFirst().orElseThrow();
    }

    /** RuleOutcome 为不可变值对象但未实现 equals，按全部字段逐字比较两份复盘报告。 */
    private static void assertReportEquals(ReplayReport a, ReplayReport b, String message) {
        assertEquals(a.contractId(), b.contractId(), message);
        assertEquals(a.judgedAt(), b.judgedAt(), message);
        assertEquals(a.batchCount(), b.batchCount(), message);
        assertEquals(a.violatingBatchCount(), b.violatingBatchCount(), message);
        assertEquals(a.batches().size(), b.batches().size(), message);
        for (int i = 0; i < a.batches().size(); i++) {
            ReplayBatchView va = a.batches().get(i);
            ReplayBatchView vb = b.batches().get(i);
            assertEquals(va.batchId(), vb.batchId(), message);
            assertEquals(va.businessTime(), vb.businessTime(), message);
            assertEquals(va.carriedStatus(), vb.carriedStatus(), message);
            assertEquals(va.violatedRuleIds(), vb.violatedRuleIds(), message);
            assertEquals(va.freshnessOutcomes().size(), vb.freshnessOutcomes().size(), message);
            for (int j = 0; j < va.freshnessOutcomes().size(); j++) {
                RuleOutcome oa = va.freshnessOutcomes().get(j);
                RuleOutcome ob = vb.freshnessOutcomes().get(j);
                assertEquals(oa.getRuleId(), ob.getRuleId(), message);
                assertEquals(oa.getRuleVersion(), ob.getRuleVersion(), message);
                assertEquals(oa.isPassed(), ob.isPassed(), message);
                assertEquals(oa.getMeasured(), ob.getMeasured(), message);
                assertEquals(oa.getExpected(), ob.getExpected(), message);
                assertEquals(oa.getBasis(), ob.getBasis(), message);
            }
        }
    }

    @Test
    @DisplayName("业务时点距判定时刻在窗口内：新鲜批次照常通过")
    void freshBatchPasses() {
        BatchResult r = service.submit(batch("b-fresh", T0, T0.plusSeconds(3000)),
                T0.plusSeconds(1800));
        log.info("[新鲜通过] 批次={} measured={} basis={}", r.getBatchId(),
                freshnessOf(r).getMeasured(), freshnessOf(r).getBasis());

        assertEquals(BatchStatus.PASSED, r.getStatus());
        assertTrue(freshnessOf(r).isPassed());
        assertEquals("1800s", freshnessOf(r).getMeasured());
    }

    @Test
    @DisplayName("业务时点远早于判定时刻（迟到旧数据）：明确判失败并进告警")
    void staleBatchFailsAndOpensAlert() {
        Instant business = T0.minusSeconds(7200);
        Instant judged = T0;
        BatchResult r = service.submit(batch("b-stale", business, T0.minusSeconds(10)), judged);
        log.info("[陈旧失败] 批次={} measured={} basis={}", r.getBatchId(),
                freshnessOf(r).getMeasured(), freshnessOf(r).getBasis());

        assertEquals(BatchStatus.FAILED, r.getStatus());
        RuleOutcome o = freshnessOf(r);
        assertFalse(o.isPassed());
        assertEquals("7200s", o.getMeasured());
        assertTrue(o.getBasis().contains("业务时点=" + business));
        assertTrue(o.getBasis().contains("判定时刻=" + judged));
        Alert alert = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, alert.getState());
        assertEquals(1, alert.getOccurrenceCount());
    }

    @Test
    @DisplayName("相同业务时点与判定时刻，无论何时计算多少次，结论与依据完全一致（不随运行时刻漂移）")
    void resultIsDeterministicRegardlessOfWallClock() throws Exception {
        Instant business = T0.minusSeconds(5400);
        Instant judged = T0;
        BatchResult first = service.submit(batch("b-det-1", business, T0), judged);

        Thread.sleep(20);
        BatchResult again = service.submit(batch("b-det-1", business, T0), judged.plusSeconds(999));
        // 重复提交：判定时刻不同也必须返回既有结论，结论不漂移
        assertEquals(first.getStatus(), again.getStatus());
        assertEquals(first.getJudgedAt(), again.getJudgedAt());
        assertEquals(first.getOutcomes().get(0).getBasis(), again.getOutcomes().get(0).getBasis());
        assertTrue(again.isDuplicate());

        // 另一个同内容新批次在同一判定时刻下，结论依据逐字一致
        BatchResult other = service.submit(batch("b-det-2", business, T0), judged);
        assertEquals(first.getOutcomes().get(0).getMeasured(), other.getOutcomes().get(0).getMeasured());
        assertEquals(first.getOutcomes().get(0).getBasis(), other.getOutcomes().get(0).getBasis());
        log.info("[可复现] 两次结论 basis 一致: {}", other.getOutcomes().get(0).getBasis());
    }

    @Test
    @DisplayName("迟到乱序不倒退告警：恢复/关闭后更旧业务时点的越界批次不能重新打开；更晚越界仍会重开")
    void lateArrivalsDoNotRegressAlert() {
        // 业务 08:00、10:00 才到达判定：滞后 2h 越界 -> OPEN
        service.submit(batch("b-0800", T0.minusSeconds(2 * 3600), T0), T0);
        // 业务 11:00、11:30 到达判定：滞后 1800s 恢复 -> RESOLVED
        service.submit(batch("b-1100", T0.plusSeconds(3600), T0.plusSeconds(5400)),
                T0.plusSeconds(5400));
        Alert resolved = service.listAlerts().get(0);
        assertEquals(AlertState.RESOLVED, resolved.getState());

        // 迟到补交业务时点 09:00，12:00 才到达（滞后 3h 越界）：按到达顺序本会重开，必须被抑制
        BatchResult late = service.submit(batch("b-0900-late", T0.minusSeconds(3600),
                T0.plusSeconds(2 * 3600)), T0.plusSeconds(2 * 3600));
        assertEquals(BatchStatus.FAILED, late.getStatus(), "迟到批次自身结论仍按其业务时点判失败");
        Alert afterLate = service.listAlerts().get(0);
        assertEquals(AlertState.RESOLVED, afterLate.getState(), "迟到旧数据不得重开已恢复告警");
        assertEquals(1, afterLate.getOpenCount(), "抑制不得新增打开次数");
        assertEquals(1, afterLate.getOccurrenceCount(), "抑制不得累计越界次数");
        assertEquals("b-1100", afterLate.getLastBatchId(), "最近批次不得被迟到旧数据覆盖");
        long suppressed = afterLate.getTransitions().stream()
                .filter(t -> t.note() != null && t.note().contains("抑制")).count();
        assertEquals(1, suppressed, "迟到抑制必须留痕");
        log.info("[迟到抑制] transitions={}", afterLate.getTransitions().size());

        // 业务时点 12:00、14:00 到达判定，滞后 2h 越界：业务时序确实更晚，必须重新打开
        BatchResult later = service.submit(batch("b-1200", T0.plusSeconds(2 * 3600),
                T0.plusSeconds(4 * 3600)), T0.plusSeconds(4 * 3600));
        assertEquals(BatchStatus.FAILED, later.getStatus());
        Alert afterReopen = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, afterReopen.getState(), "更晚业务时点越界必须重开");
        assertEquals(2, afterReopen.getOpenCount());
        assertEquals(2, afterReopen.getOccurrenceCount());
    }

    @Test
    @DisplayName("迟到的通过数据不得把 OPEN 告警提前关闭")
    void latePassingBatchDoesNotResolveOpenAlert() {
        // 业务 12:00、14:00 到达判定：滞后 2h 越界 OPEN
        service.submit(batch("c-1200", T0.plusSeconds(2 * 3600), T0.plusSeconds(4 * 3600)),
                T0.plusSeconds(4 * 3600));
        // 迟到补交业务 09:00、按 09:30 口径本可通过：不得关闭当前 OPEN 告警
        BatchResult latePass = service.submit(batch("c-0900-pass", T0.minusSeconds(3600),
                T0.minusSeconds(1800)), T0.minusSeconds(1800));
        assertEquals(BatchStatus.PASSED, latePass.getStatus());
        Alert alert = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, alert.getState());
        assertEquals(1, alert.getOccurrenceCount());
    }

    @Test
    @DisplayName("迟到批次并发重复提交：只留一条抑制记录，结论不漂移、计数不重复")
    void concurrentLateSubmissionsSuppressedOnce() throws Exception {
        // 先让规则经历 打开(业务08:00) -> 恢复(业务11:00)
        service.submit(batch("d-0800", T0.minusSeconds(2 * 3600), T0), T0);
        service.submit(batch("d-1100", T0.plusSeconds(3600), T0.plusSeconds(5400)),
                T0.plusSeconds(5400));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BatchResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 同一迟到业务时点 09:00、同一 batchId、同一内容
                    results.add(service.submit(
                            batch("d-0900-late", T0.minusSeconds(3600), T0.plusSeconds(2 * 3600)),
                            T0.plusSeconds(2 * 3600)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(3, service.listBatches().size(), "并发迟到批次只落一份");
        assertEquals(threads, results.size());
        Alert alert = service.listAlerts().get(0);
        assertEquals(AlertState.RESOLVED, alert.getState());
        assertEquals(1, alert.getOpenCount());
        long suppressed = alert.getTransitions().stream()
                .filter(t -> t.note() != null && t.note().contains("抑制")).count();
        assertEquals(1, suppressed, "并发重复迟到只允许一条抑制留痕");
        log.info("[并发迟到] 结果数={} 抑制记录={}", results.size(), suppressed);
    }

    @Test
    @DisplayName("只读复盘不改历史：重算结论正确、同刻重复一致，批次结论/告警/流转原样保留")
    void replayIsReadOnlyConsistentAndAccurate() {
        // 业务 08:00、10:00 判定 -> 滞后 2h 失败；业务 10:30、10:30:10 判定 -> 通过
        service.submit(batch("e-0800", T0.minusSeconds(2 * 3600), T0), T0);
        service.submit(batch("e-1030", T0.plusSeconds(1800), T0.plusSeconds(1810)),
                T0.plusSeconds(1810));

        // 快照：复盘前的历史状态（OPEN -> RESOLVED 共两条流转）
        int batchesBefore = service.listBatches().size();
        Alert alertBefore = service.listAlerts().get(0);
        int transitionsBefore = alertBefore.getTransitions().size();
        String storedStatusBasis = service.getBatch("e-0800").orElseThrow()
                .getOutcomes().get(0).getBasis();

        // 在 11:00 复盘：08:00 滞后 3h 越界；10:30 滞后 30min 在窗口内
        Instant replayAt = T0.plusSeconds(3600);
        ReplayReport r1 = service.replay(CONTRACT, replayAt);
        ReplayReport r2 = service.replay(CONTRACT, replayAt);

        assertEquals(2, r1.batchCount());
        assertEquals(1, r1.violatingBatchCount());
        assertReportEquals(r1, r2, "同一判定时刻重复复盘结果必须一致");

        Map<String, ReplayBatchView> byId = new HashMap<>();
        r1.batches().forEach(v -> byId.put(v.batchId(), v));
        ReplayBatchView oldOne = byId.get("e-0800");
        ReplayBatchView newOne = byId.get("e-1030");
        assertEquals(List.of("r-fresh"), oldOne.violatedRuleIds());
        assertTrue(newOne.violatedRuleIds().isEmpty());
        assertEquals(replayAt, oldOne.judgedAt());
        assertEquals("10800s", oldOne.freshnessOutcomes().get(0).getMeasured());
        assertTrue(oldOne.freshnessOutcomes().get(0).getBasis().contains("[复盘]"));
        // 复盘明细顺序按业务时点稳定排序
        assertEquals(List.of("e-0800", "e-1030"),
                r1.batches().stream().map(ReplayBatchView::batchId).toList());

        // 更早时刻复盘（09:00 整）：08:00 恰好窗口边界通过；10:30 晚于判定时刻（滞后为负）也通过
        ReplayReport early = service.replay(CONTRACT, T0.minusSeconds(3600));
        assertEquals(0, early.violatingBatchCount());

        // 历史零改动断言
        assertEquals(batchesBefore, service.listBatches().size());
        Alert alertAfter = service.listAlerts().get(0);
        assertEquals(transitionsBefore, alertAfter.getTransitions().size());
        assertEquals(alertBefore.getState(), alertAfter.getState());
        assertEquals(storedStatusBasis, service.getBatch("e-0800").orElseThrow()
                .getOutcomes().get(0).getBasis());
        log.info("[复盘只读] 越界批次={} 历史告警状态={} 流转数={}",
                r1.violatingBatchCount(), alertAfter.getState(), alertAfter.getTransitions().size());
    }

    @Test
    @DisplayName("复盘范围在几百批次量级下可快速完成且结论完整")
    void replayScalesToHundredsOfBatches() {
        Instant base = T0;
        int n = 500;
        List<BatchSubmission> pending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 业务时点均匀分布在 T0 前 100 小时内，到达时间统一在 T0
            Instant business = base.minus(Duration.ofHours(100)).plusSeconds(600L * i);
            pending.add(batch("f-" + i, business, T0));
        }
        for (int i = 0; i < n; i++) {
            service.submit(pending.get(i), T0);
        }

        long startNanos = System.nanoTime();
        ReplayReport report = service.replay(CONTRACT, T0);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(n, report.batchCount());
        // 窗口 1h：业务时点早于 T0-1h 的批次都应判越界
        long expectedViolations = pending.stream()
                .filter(b -> b.effectiveBusinessTime().isBefore(T0.minusSeconds(MAX_AGE)))
                .count();
        assertEquals(expectedViolations, report.violatingBatchCount());
        assertTrue(elapsedMs < 3000, "500 批次复盘应在 3 秒内完成，实际 " + elapsedMs + "ms");
        log.info("[复盘规模] 批次={} 越界={} 耗时={}ms", n, report.violatingBatchCount(), elapsedMs);
    }
}
