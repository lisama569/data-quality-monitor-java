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
import com.github.lisama569.dataqualitymonitorjava.model.FreshnessReview;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.RuleOutcome;
import com.github.lisama569.dataqualitymonitorjava.model.RuleType;
import com.github.lisama569.dataqualitymonitorjava.service.AlertService;
import com.github.lisama569.dataqualitymonitorjava.service.ContentHasher;
import com.github.lisama569.dataqualitymonitorjava.service.DataQualityService;
import com.github.lisama569.dataqualitymonitorjava.service.QualityEvaluator;
import com.github.lisama569.dataqualitymonitorjava.service.RuleValidator;
import com.github.lisama569.dataqualitymonitorjava.storage.LocalStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新鲜度（业务时点 -&gt; 判定时刻滞后）、迟到/回填与只读复盘的行为测试。
 * 服务层日志会输出批次标识、命中规则与判定依据。
 */
class FreshnessTimelinessTest {

    private static final Logger log = LoggerFactory.getLogger(FreshnessTimelinessTest.class);

    private static final String CONTRACT = "fresh-contract";
    private static final Instant T0 = Instant.parse("2026-09-24T08:00:00Z");

    private LocalStorage storage;
    private DataQualityService service;

    @BeforeEach
    void setUp() {
        storage = new LocalStorage();
        service = new DataQualityService(storage, new ContentHasher(), new RuleValidator(),
                new QualityEvaluator(), new AlertService(storage));
        service.registerContract(new DataContract(CONTRACT, 1, List.of(
                new FieldSchema("id", DataType.INTEGER, true),
                new FieldSchema("status", DataType.STRING, true)
        ), T0));
    }

    private QualityRule freshnessRule(String id, long maxAgeSeconds) {
        return new QualityRule(CONTRACT, id, 0, RuleType.FRESHNESS, null, null, null,
                null, null, maxAgeSeconds, T0);
    }

    private QualityRule completenessRule(String id, double threshold) {
        return new QualityRule(CONTRACT, id, 0, RuleType.COMPLETENESS, "id", threshold, null,
                null, null, null, T0);
    }

    private DataRow row(Object id) {
        HashMap<String, String> m = new HashMap<>();
        m.put("id", id == null ? null : String.valueOf(id));
        m.put("status", "NEW");
        return new DataRow(m);
    }

    /** 显式业务时点 + 显式判定时刻的批次。 */
    private BatchSubmission batch(String batchId, Instant businessTime, Instant evaluationTime,
                                  List<DataRow> rows) {
        return new BatchSubmission(batchId, CONTRACT, businessTime, businessTime, evaluationTime, rows);
    }

    private void printResult(BatchResult result) {
        log.info("[测试断言] 批次标识={} 状态={} 重复={} 业务时点={} 判定时刻={}",
                result.getBatchId(), result.getStatus(), result.isDuplicate(),
                result.getBusinessTime(), result.getEvaluatedAt());
        for (RuleOutcome o : result.getOutcomes()) {
            log.info("[测试断言] 批次标识={} 命中规则={} v{} 类型={} 判定={} measured={} expected={} 依据={}",
                    result.getBatchId(), o.getRuleId(), o.getRuleVersion(), o.getRuleType(),
                    o.isPassed() ? "PASS" : "FAIL", o.getMeasured(), o.getExpected(), o.getBasis());
        }
    }

    private Alert onlyAlert() {
        List<Alert> alerts = service.listAlerts();
        assertEquals(1, alerts.size());
        return alerts.get(0);
    }

    @Test
    @DisplayName("新鲜批次：缺省判定时刻取系统当前时间，业务时点很近时照常通过")
    void freshBatchPassesWithDefaultEvaluationTime() {
        service.registerValidatedRule(freshnessRule("r-fresh", 3600));
        // 不显式指定判定时刻 -> 服务层取系统当前时间；业务时点就是现在，滞后约 0。
        BatchResult result = service.submit(
                new BatchSubmission("b-fresh-now", CONTRACT, Instant.now(), List.of(row(1))));
        printResult(result);

        assertEquals(BatchStatus.PASSED, result.getStatus());
        RuleOutcome freshness = result.getOutcomes().get(0);
        assertTrue(freshness.isPassed());
        assertTrue(freshness.getBasis().contains("业务时点="), "判定依据必须含业务时点");
        assertTrue(freshness.getBasis().contains("判定时刻="), "判定依据必须含判定时刻");
        assertNotNull(result.getEvaluatedAt(), "判定时刻必须随结论落库");
    }

    @Test
    @DisplayName("陈旧批次：业务时点远早于判定时刻，明确判 FAILED 并打开告警")
    void staleBatchFailsAndOpensAlert() {
        service.registerValidatedRule(freshnessRule("r-fresh", 3600));
        // 回填场景：业务时点在两天前，判定时刻取系统当前时间。
        BatchResult result = service.submit(new BatchSubmission("b-stale", CONTRACT,
                Instant.now().minus(2, ChronoUnit.DAYS), List.of(row(1))));
        printResult(result);

        assertEquals(BatchStatus.FAILED, result.getStatus());
        RuleOutcome freshness = result.getOutcomes().get(0);
        assertFalse(freshness.isPassed());
        assertTrue(freshness.getBasis().contains("超出新鲜度窗口"));
        Alert alert = onlyAlert();
        assertEquals(AlertState.OPEN, alert.getState());
        assertEquals(1, alert.getOccurrenceCount());
        log.info("[测试断言] 陈旧批次告警 rule={} state={} occurrence={}",
                alert.getRuleId(), alert.getState(), alert.getOccurrenceCount());
    }

    @Test
    @DisplayName("指定判定时刻：同一业务时点+判定时刻，结论与依据可复现，不随运行时刻漂移")
    void explicitEvaluationTimeIsReproducible() {
        service.registerValidatedRule(freshnessRule("r-fresh", 3600));

        // 滞后 1800 秒 <= 3600 -> 通过；判定依据中的两个时刻均为显式指定值。
        BatchResult first = service.submit(batch("b-replayable", T0, T0.plusSeconds(1800),
                List.of(row(1))));
        printResult(first);
        assertEquals(BatchStatus.PASSED, first.getStatus());
        assertEquals(T0.plusSeconds(1800), first.getEvaluatedAt(), "判定时刻必须是指定值而非运行时刻");
        RuleOutcome outcome = first.getOutcomes().get(0);
        assertEquals("1800s", outcome.getMeasured());
        assertTrue(outcome.getBasis().contains("业务时点=" + T0));
        assertTrue(outcome.getBasis().contains("判定时刻=" + T0.plusSeconds(1800)));

        // 同一批次重复提交（同一业务时点+判定时刻）：返回完全一致的既有结论。
        BatchResult dup = service.submit(batch("b-replayable", T0, T0.plusSeconds(1800),
                List.of(row(1))));
        printResult(dup);
        assertTrue(dup.isDuplicate());
        assertEquals(first.getStatus(), dup.getStatus());
        assertEquals(first.getOutcomes().get(0).getBasis(), dup.getOutcomes().get(0).getBasis());
        assertEquals(1, service.listBatches().size());

        // 同一业务时点、更晚的判定时刻（滞后 7200 秒 > 3600）-> 另一批次明确失败。
        BatchResult later = service.submit(batch("b-replayable-late-view", T0, T0.plusSeconds(7200),
                List.of(row(1))));
        printResult(later);
        assertEquals(BatchStatus.FAILED, later.getStatus());
        assertEquals("7200s", later.getOutcomes().get(0).getMeasured());
    }

    @Test
    @DisplayName("迟到乱序：已恢复的告警不被业务时点更早的迟到批次拉回，更晚越界数据仍会重开")
    void lateOutOfOrderBatchDoesNotRegressAlert() {
        service.registerValidatedRule(completenessRule("r-id-complete", 1.0));

        // b1 业务时点 T0+100：缺 id -> OPEN。
        BatchResult b1 = service.submit(batch("b-late-1", T0.plusSeconds(100), T0.plusSeconds(100),
                List.of(row(null))));
        printResult(b1);
        assertEquals(AlertState.OPEN, onlyAlert().getState());

        // b2 业务时点 T0+200（更晚）：id 齐全 -> RESOLVED。
        BatchResult b2 = service.submit(batch("b-late-2", T0.plusSeconds(200), T0.plusSeconds(200),
                List.of(row(2))));
        printResult(b2);
        Alert resolved = onlyAlert();
        assertEquals(AlertState.RESOLVED, resolved.getState());
        int transitionsAfterResolve = resolved.getTransitions().size();

        // b3 迟到批次：业务时点 T0+50（早于水位 T0+200）且越界 -> 不得重开、不得累计。
        BatchResult b3 = service.submit(batch("b-late-3", T0.plusSeconds(50), T0.plusSeconds(300),
                List.of(row(null))));
        printResult(b3);
        assertEquals(BatchStatus.FAILED, b3.getStatus(), "迟到批次自身结论仍按规则判定");
        Alert afterLate = onlyAlert();
        assertEquals(AlertState.RESOLVED, afterLate.getState(), "迟到旧数据不得把告警拉回 OPEN");
        assertEquals(1, afterLate.getOccurrenceCount(), "迟到批次不得累计越界次数");
        assertEquals(1, afterLate.getOpenCount());
        assertEquals(transitionsAfterResolve, afterLate.getTransitions().size(),
                "迟到批次不得追加流转历史");
        log.info("[测试断言] 迟到批次被忽略后告警 state={} occurrence={} transitions={}",
                afterLate.getState(), afterLate.getOccurrenceCount(),
                afterLate.getTransitions().size());

        // b4 业务时点 T0+400（更晚）且越界 -> 必须重新打开。
        BatchResult b4 = service.submit(batch("b-late-4", T0.plusSeconds(400), T0.plusSeconds(400),
                List.of(row(null))));
        printResult(b4);
        Alert reopened = onlyAlert();
        assertEquals(AlertState.OPEN, reopened.getState(), "更晚的越界数据必须重新打开告警");
        assertEquals(2, reopened.getOpenCount());
        assertEquals(2, reopened.getOccurrenceCount());
        assertEquals(transitionsAfterResolve + 1, reopened.getTransitions().size());
    }

    @Test
    @DisplayName("迟到批次并发重复提交：只评估一次，告警不重复累计、状态不倒退")
    void concurrentLateDuplicatesDoNotDoubleCount() throws Exception {
        service.registerValidatedRule(completenessRule("r-id-complete", 1.0));

        // 建立水位：业务时点 T0+200 的失败批次 -> OPEN，occurrence=1。
        service.submit(batch("b-wm", T0.plusSeconds(200), T0.plusSeconds(200), List.of(row(null))));
        Alert opened = onlyAlert();
        assertEquals(AlertState.OPEN, opened.getState());
        assertEquals(1, opened.getOccurrenceCount());

        // 16 线程并发提交同一迟到批次（业务时点 T0+50，越界）。
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BatchResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(service.submit(batch("b-late-concurrent", T0.plusSeconds(50),
                            T0.plusSeconds(300), List.of(row(null)))));
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
        assertEquals(1, results.stream().filter(r -> !r.isDuplicate()).count(),
                "并发迟到重复提交恰好只评估一次");
        assertEquals(2, service.listBatches().size(), "迟到批次只落一条批次结论");
        Alert after = onlyAlert();
        assertEquals(AlertState.OPEN, after.getState());
        assertEquals(1, after.getOccurrenceCount(), "并发迟到提交不得重复累计告警次数");
        assertEquals(1, after.getTransitions().size(), "并发迟到提交不得追加流转历史");
        log.info("[测试断言] 并发迟到重复提交后告警 state={} occurrence={} transitions={}",
                after.getState(), after.getOccurrenceCount(), after.getTransitions().size());
    }

    @Test
    @DisplayName("只读复盘：按指定时刻重算越界情况，不改写批次结论与告警历史，重复复盘结果一致")
    void replayIsReadOnlyAndDeterministic() {
        service.registerValidatedRule(freshnessRule("r-fresh", 3600));

        // 三个批次：业务时点分别为 T0、T0+1800、T0+7200，提交时判定时刻=业务时点（均通过）。
        List<String> ids = List.of("b-rp-1", "b-rp-2", "b-rp-3");
        List<Instant> businessTimes = List.of(T0, T0.plusSeconds(1800), T0.plusSeconds(7200));
        for (int i = 0; i < ids.size(); i++) {
            BatchResult r = service.submit(batch(ids.get(i), businessTimes.get(i),
                    businessTimes.get(i), List.of(row(i + 1))));
            printResult(r);
            assertEquals(BatchStatus.PASSED, r.getStatus());
        }
        // 再制造一条告警历史：陈旧批次 -> OPEN。
        BatchResult stale = service.submit(batch("b-rp-stale", T0.minusSeconds(7200),
                T0.plusSeconds(7200), List.of(row(9))));
        printResult(stale);
        assertEquals(BatchStatus.FAILED, stale.getStatus());
        Alert alertBefore = onlyAlert();
        List<BatchResult> batchesBefore = new ArrayList<>(service.listBatches());

        // 复盘时刻 T0+7200：b-rp-1 滞后 7200、b-rp-2 滞后 5400 -> 越界；b-rp-3 滞后 0 -> 不越界。
        Instant reviewTime = T0.plusSeconds(7200);
        FreshnessReview review1 = service.reviewFreshness(CONTRACT, reviewTime);
        FreshnessReview review2 = service.reviewFreshness(CONTRACT, reviewTime);
        log.info("[测试断言] 复盘 reviewTime={} 覆盖批次={} 越界批次={}",
                review1.reviewTime(), review1.batchCount(), review1.violatedCount());
        for (FreshnessReview.Entry e : review1.entries()) {
            log.info("[测试断言] 复盘条目 批次标识={} 业务时点={} 越界={} 评估={}",
                    e.batchId(), e.businessTime(), e.violated(), e.assessments());
        }

        assertEquals(review1, review2, "同一判定时刻重复复盘结果必须完全一致");
        assertEquals(4, review1.batchCount());
        // b-rp-1(7200s)、b-rp-2(5400s)、b-rp-stale(14400s) 越界；b-rp-3(0s) 不越界。
        assertEquals(3, review1.violatedCount());
        FreshnessReview.Entry e1 = entry(review1, "b-rp-1");
        assertTrue(e1.violated());
        assertEquals(7200, e1.assessments().get(0).lagSeconds());
        assertFalse(entry(review1, "b-rp-3").violated());
        assertEquals(0, entry(review1, "b-rp-3").assessments().get(0).lagSeconds());

        // 只读保证：批次结论与告警对象均未被改写（存储中为同一对象引用）。
        List<BatchResult> batchesAfter = service.listBatches();
        assertEquals(batchesBefore.size(), batchesAfter.size());
        for (int i = 0; i < batchesBefore.size(); i++) {
            assertSame(batchesBefore.get(i), batchesAfter.get(i),
                    "复盘不得改写已落地的批次结论");
        }
        assertSame(alertBefore, onlyAlert(), "复盘不得改写告警状态与流转历史");
        assertEquals(AlertState.OPEN, onlyAlert().getState());
    }

    @Test
    @DisplayName("复盘规模：数百个批次一次复盘可完成且结果确定")
    void replayScalesToHundredsOfBatches() {
        service.registerValidatedRule(freshnessRule("r-fresh", 3600));
        int total = 300;
        for (int i = 0; i < total; i++) {
            // 一半批次业务时点较新，一半较旧；判定时刻=业务时点，提交时全部通过。
            Instant bt = T0.plusSeconds(i % 2 == 0 ? 100 : 7200);
            service.submit(batch("b-scale-" + i, bt, bt, List.of(row(i))));
        }
        assertEquals(total, service.listBatches().size());

        Instant reviewTime = T0.plusSeconds(3600);
        FreshnessReview review = service.reviewFreshness(CONTRACT, reviewTime);
        log.info("[测试断言] 规模复盘 reviewTime={} 覆盖批次={} 越界批次={}",
                review.reviewTime(), review.batchCount(), review.violatedCount());
        assertEquals(total, review.batchCount());
        // 业务时点 T0+7200 的一半滞后为负（在窗口内），T0+100 的一半滞后 3500（<=3600）-> 均不越界。
        assertEquals(0, review.violatedCount());

        FreshnessReview later = service.reviewFreshness(CONTRACT, T0.plusSeconds(7200));
        // 此时 T0+100 的一半滞后 7100 > 3600 -> 越界；T0+7200 的一半滞后 0 -> 不越界。
        assertEquals(total / 2, later.violatedCount());
        assertEquals(later, service.reviewFreshness(CONTRACT, T0.plusSeconds(7200)),
                "同一判定时刻下大规模复盘结果必须可复现");
    }

    private FreshnessReview.Entry entry(FreshnessReview review, String batchId) {
        return review.entries().stream()
                .filter(e -> e.batchId().equals(batchId)).findFirst().orElseThrow();
    }
}
