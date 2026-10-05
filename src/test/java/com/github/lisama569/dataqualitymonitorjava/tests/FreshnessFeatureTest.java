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

    @Test
    @DisplayName("同一业务时点连续到达：越界打开 -> 同时点越界合并 -> 同时点通过恢复 -> 同时点越界重开")
    void sameBusinessTimeBatchesParticipateInTransitions() {
        // 业务时点按整点取整，同一时点 T0 连续到达 4 个批次
        Instant biz = T0;
        // 1) 越界（滞后 2h）：首次打开
        BatchResult r1 = service.submit(batch("s-1", biz, T0.plusSeconds(2 * 3600)),
                T0.plusSeconds(2 * 3600));
        assertEquals(BatchStatus.FAILED, r1.getStatus());
        Alert a1 = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, a1.getState());
        assertEquals(1, a1.getOccurrenceCount());
        assertEquals(1, a1.getOpenCount());
        assertEquals(biz, a1.getWatermarkBusinessTime());

        // 2) 同一业务时点再越界（滞后 3h）：必须合并累计，而不是被水位抑制
        BatchResult r2 = service.submit(batch("s-2", biz, T0.plusSeconds(3 * 3600)),
                T0.plusSeconds(3 * 3600));
        assertEquals(BatchStatus.FAILED, r2.getStatus());
        Alert a2 = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, a2.getState(), "同时点越界应合并到既有告警");
        assertEquals(2, a2.getOccurrenceCount(), "同时点越界必须累计越界次数");
        assertEquals(1, a2.getOpenCount(), "合并不重复打开");
        assertEquals("s-2", a2.getLastBatchId());

        // 3) 同一业务时点通过（滞后 30min）：必须把已打开的告警恢复关闭
        BatchResult r3 = service.submit(batch("s-3", biz, T0.plusSeconds(1800)),
                T0.plusSeconds(1800));
        assertEquals(BatchStatus.PASSED, r3.getStatus());
        Alert a3 = service.listAlerts().get(0);
        assertEquals(AlertState.RESOLVED, a3.getState(), "同时点通过必须恢复关闭告警");
        assertEquals(2, a3.getOccurrenceCount(), "通过不累计越界次数");
        assertEquals(1, a3.getOpenCount());

        // 4) 同一业务时点再越界（滞后 2h）：必须能重新打开，不能卡在已恢复
        BatchResult r4 = service.submit(batch("s-4", biz, T0.plusSeconds(2 * 3600)),
                T0.plusSeconds(2 * 3600));
        assertEquals(BatchStatus.FAILED, r4.getStatus());
        Alert a4 = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, a4.getState(), "同时点再越界必须重新打开");
        assertEquals(3, a4.getOccurrenceCount());
        assertEquals(2, a4.getOpenCount(), "重开必须累计打开次数");
        assertEquals("s-4", a4.getLastBatchId());

        long suppressed = a4.getTransitions().stream()
                .filter(t -> t.note() != null && t.note().contains("抑制")).count();
        assertEquals(0, suppressed, "同一业务时点不属于迟到，不得产生抑制记录");
        // 完整流转：打开 -> 合并 -> 恢复 -> 重开
        assertEquals(4, a4.getTransitions().size());
        log.info("[同时点流转] transitions={}", a4.getTransitions().stream()
                .map(t -> t.from() + "->" + t.to() + "@" + t.batchId()).toList());
    }

    @Test
    @DisplayName("迟到抑制边界：业务时点早 1 秒才抑制（越界与通过都抑制），等于水位照常流转，晚 1 秒重开")
    void lateSuppressionBoundaryIsStrictlyEarlier() {
        // 基准：业务 T0 越界打开，水位 = T0
        service.submit(batch("t-base", T0, T0.plusSeconds(2 * 3600)), T0.plusSeconds(2 * 3600));
        Alert opened = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, opened.getState());
        assertEquals(T0, opened.getWatermarkBusinessTime());

        // 早 1 秒的越界批次：抑制，状态/计数/最近批次均不变，仅留痕
        BatchResult earlyFail = service.submit(batch("t-early-fail", T0.minusSeconds(1),
                T0.plusSeconds(3 * 3600)), T0.plusSeconds(3 * 3600));
        assertEquals(BatchStatus.FAILED, earlyFail.getStatus(), "迟到批次自身结论仍按业务时点判定");
        Alert afterEarlyFail = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, afterEarlyFail.getState());
        assertEquals(1, afterEarlyFail.getOccurrenceCount(), "抑制不得累计越界次数");
        assertEquals(1, afterEarlyFail.getOpenCount(), "抑制不得累计打开次数");
        assertEquals("t-base", afterEarlyFail.getLastBatchId(), "最近批次不得被迟到批次覆盖");
        assertEquals(T0, afterEarlyFail.getWatermarkBusinessTime(), "水位不得被迟到批次回退");
        assertEquals(1, afterEarlyFail.getTransitions().stream()
                .filter(t -> t.note() != null && t.note().contains("抑制")).count());

        // 等于水位的通过批次：照常流转，恢复关闭
        service.submit(batch("t-equal-pass", T0, T0.plusSeconds(1800)), T0.plusSeconds(1800));
        Alert afterEqualPass = service.listAlerts().get(0);
        assertEquals(AlertState.RESOLVED, afterEqualPass.getState(), "等于水位不算迟到，必须照常恢复");

        // 早 1 秒的通过批次：同样抑制，不得改动已恢复状态与任何计数
        BatchResult earlyPass = service.submit(batch("t-early-pass", T0.minusSeconds(1),
                T0.minusSeconds(1800)), T0.minusSeconds(1800));
        assertEquals(BatchStatus.PASSED, earlyPass.getStatus());
        Alert afterEarlyPass = service.listAlerts().get(0);
        assertEquals(AlertState.RESOLVED, afterEarlyPass.getState());
        assertEquals(1, afterEarlyPass.getOccurrenceCount());
        assertEquals(1, afterEarlyPass.getOpenCount());
        assertEquals("t-equal-pass", afterEarlyPass.getLastBatchId());
        assertEquals(2, afterEarlyPass.getTransitions().stream()
                .filter(t -> t.note() != null && t.note().contains("抑制")).count(),
                "越界与通过的迟到批次各留一条抑制记录");

        // 晚 1 秒的越界批次：业务时序更晚，必须重新打开
        service.submit(batch("t-later-fail", T0.plusSeconds(1), T0.plusSeconds(2 * 3600)),
                T0.plusSeconds(2 * 3600));
        Alert afterLater = service.listAlerts().get(0);
        assertEquals(AlertState.OPEN, afterLater.getState(), "更晚业务时点越界必须重开");
        assertEquals(2, afterLater.getOccurrenceCount());
        assertEquals(2, afterLater.getOpenCount());
        assertEquals(T0.plusSeconds(1), afterLater.getWatermarkBusinessTime());
        log.info("[抑制边界] transitions={}", afterLater.getTransitions().stream()
                .map(t -> t.from() + "->" + t.to() + "@" + t.batchId()).toList());
    }

    @Test
    @DisplayName("不同 batchId 的迟到批次并发提交：各自只真实处理一次，抑制留痕与批次一一对应，计数零累计")
    void concurrentDistinctLateBatchesEachSuppressedOnce() throws Exception {
        // 先让规则经历 打开(业务08:00) -> 恢复(业务11:00)，水位 = 11:00
        service.submit(batch("g-0800", T0.minusSeconds(2 * 3600), T0), T0);
        service.submit(batch("g-1100", T0.plusSeconds(3600), T0.plusSeconds(5400)),
                T0.plusSeconds(5400));

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BatchResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            String batchId = "g-late-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 不同 batchId、同一迟到业务时点 09:00（早于水位），均越界
                    results.add(service.submit(
                            batch(batchId, T0.minusSeconds(3600), T0.plusSeconds(2 * 3600)),
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

        assertEquals(threads, results.size());
        assertEquals(2 + threads, service.listBatches().size(), "每个迟到批次真实落库一次");
        assertTrue(results.stream().allMatch(r -> r.getStatus() == BatchStatus.FAILED));
        assertTrue(results.stream().noneMatch(BatchResult::isDuplicate));

        Alert alert = service.listAlerts().get(0);
        assertEquals(AlertState.RESOLVED, alert.getState(), "并发迟到不得翻转已恢复状态");
        assertEquals(1, alert.getOccurrenceCount(), "并发迟到不得累计越界次数");
        assertEquals(1, alert.getOpenCount(), "并发迟到不得累计打开次数");
        assertEquals("g-1100", alert.getLastBatchId(), "最近批次不得被迟到批次覆盖");
        long suppressed = alert.getTransitions().stream()
                .filter(t -> t.note() != null && t.note().contains("抑制")).count();
        assertEquals(threads, suppressed, "每个真实迟到批次恰好一条抑制留痕，不多不少");
        log.info("[并发迟到-多批次] 批次数={} 抑制记录={} 状态={}",
                results.size(), suppressed, alert.getState());
    }

    @Test
    @DisplayName("同一迟到批次并发提交后再次重复提交：结论不漂移，仍只有一条抑制记录")
    void repeatedLateSubmissionAfterConcurrencyStaysStable() throws Exception {
        service.submit(batch("h-0800", T0.minusSeconds(2 * 3600), T0), T0);
        service.submit(batch("h-1100", T0.plusSeconds(3600), T0.plusSeconds(5400)),
                T0.plusSeconds(5400));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BatchResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(service.submit(
                            batch("h-late", T0.minusSeconds(3600), T0.plusSeconds(2 * 3600)),
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

        // 并发过后再顺序重复提交两次：仍返回既有结论
        BatchResult again1 = service.submit(
                batch("h-late", T0.minusSeconds(3600), T0.plusSeconds(2 * 3600)),
                T0.plusSeconds(2 * 3600));
        BatchResult again2 = service.submit(
                batch("h-late", T0.minusSeconds(3600), T0.plusSeconds(2 * 3600)),
                T0.plusSeconds(2 * 3600));
        assertTrue(again1.isDuplicate() && again2.isDuplicate());
        assertEquals(results.stream().filter(r -> !r.isDuplicate()).count(), 1,
                "并发提交中只有一个是真实评估");
        assertEquals(again1.getStatus(), again2.getStatus());
        assertEquals(again1.getOutcomes().get(0).getBasis(), again2.getOutcomes().get(0).getBasis(),
                "重复提交结论依据不漂移");

        Alert alert = service.listAlerts().get(0);
        assertEquals(AlertState.RESOLVED, alert.getState());
        assertEquals(1, alert.getOccurrenceCount());
        assertEquals(1, alert.getOpenCount());
        long suppressed = alert.getTransitions().stream()
                .filter(t -> t.note() != null && t.note().contains("抑制")).count();
        assertEquals(1, suppressed, "并发+重复提交全程只允许一条抑制留痕");
        log.info("[并发+重复迟到] 抑制记录={} 告警状态={}", suppressed, alert.getState());
    }

    @Test
    @DisplayName("同一判定时刻重复复盘：报告内容一致且可直接判等（equals/hashCode），全程只读")
    void repeatedReplayReportsAreDirectlyEqualAndReadOnly() {
        // 准备 3 个批次：越界、通过、再越界，告警经历完整流转
        service.submit(batch("r-1", T0.minusSeconds(2 * 3600), T0), T0);
        service.submit(batch("r-2", T0.plusSeconds(1800), T0.plusSeconds(1810)),
                T0.plusSeconds(1810));
        service.submit(batch("r-3", T0.plusSeconds(3600), T0.plusSeconds(3 * 3600)),
                T0.plusSeconds(3 * 3600));

        // 快照复盘前状态
        int batchesBefore = service.listBatches().size();
        Alert alertBefore = service.listAlerts().get(0);
        int transitionsBefore = alertBefore.getTransitions().size();
        int occurrenceBefore = alertBefore.getOccurrenceCount();

        Instant judgedAt = T0.plusSeconds(2 * 3600);
        ReplayReport first = service.replay(CONTRACT, judgedAt);
        ReplayReport second = service.replay(CONTRACT, judgedAt);
        ReplayReport third = service.replay(CONTRACT, judgedAt);

        // 报告本身直接判等：便于去重与比对（RuleOutcome 已实现值相等）
        assertEquals(first, second, "同一判定时刻重复复盘报告必须直接判等");
        assertEquals(second, third);
        assertEquals(first.hashCode(), second.hashCode(), "相等报告的 hashCode 必须一致");
        assertNotSame(first, second, "每次复盘返回独立报告对象");
        assertEquals(3, first.batchCount());
        assertEquals(2, first.violatingBatchCount());

        // 只读：批次结论、告警状态、计数与流转历史零改动
        assertEquals(batchesBefore, service.listBatches().size());
        Alert alertAfter = service.listAlerts().get(0);
        assertEquals(alertBefore.getState(), alertAfter.getState());
        assertEquals(occurrenceBefore, alertAfter.getOccurrenceCount());
        assertEquals(transitionsBefore, alertAfter.getTransitions().size());
        assertEquals(alertBefore.getWatermarkBusinessTime(), alertAfter.getWatermarkBusinessTime(),
                "复盘不得推进业务时点水位");
        log.info("[复盘判等] 报告相等={} 越界批次={} 流转数={}",
                first.equals(second), first.violatingBatchCount(),
                alertAfter.getTransitions().size());
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
        assertEquals(r1, r2, "同一判定时刻重复复盘结果必须一致且可直接判等");
        assertEquals(r1.hashCode(), r2.hashCode());

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
