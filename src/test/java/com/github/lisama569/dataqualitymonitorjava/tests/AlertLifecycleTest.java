package com.github.lisama569.dataqualitymonitorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.batch.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.common.MetricName;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldType;
import com.github.lisama569.dataqualitymonitorjava.contract.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.contract.RuleType;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertState;
import com.github.lisama569.dataqualitymonitorjava.monitor.AlertStatus;
import com.github.lisama569.dataqualitymonitorjava.tests.support.TestFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 告警状态流转：打开 -> 重复越界合并 -> 恢复 -> 再次越界重开；
 * 规则/阈值变更只影响后续评估，历史事件携带旧版本号，结论可复现。
 */
class AlertLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(AlertLifecycleTest.class);

    /** 可手动推进的固定时钟。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(String start) {
            this.instant = Instant.parse(start);
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private BatchSubmission completenessBatch(String batchId, boolean complete) {
        Map<String, Object> bad = Map.of("id", "");
        Map<String, Object> good = Map.of("id", "x");
        List<Map<String, Object>> rows = complete ? List.of(good, good) : List.of(good, bad);
        return new BatchSubmission("ds", batchId, rows, Instant.parse("2026-09-24T09:00:00Z").toEpochMilli());
    }

    @Test
    void openSuppressResolveReopenCycle() {
        // Completeness 阈值 < 0.95 触发；50% 越界，100% 恢复
        TestFixture.Harness h = TestFixture.newService("2026-09-24T10:00:00Z");
        h.service().registerContract("ds",
                List.of(new FieldSpec("id", FieldType.STRING, true)),
                List.of(new QualityRule("r-complete", RuleType.COMPLETENESS, "id", Map.of("min", 1.0))));
        h.service().registerAlert("a-comp", "ds", MetricName.COMPLETENESS_RATIO, "<", 0.95);

        // 1) 首次越界：打开
        BatchResult b1 = h.service().submit(completenessBatch("b1", false));
        AlertStatus s1 = h.alertService().status("ds", "a-comp").orElseThrow();
        assertEquals(AlertState.FIRING, s1.state());
        assertEquals(1L, s1.firedCount());
        assertEquals(0.5, s1.lastValue(), 1e-9);

        // 2) 再次越界：合并，不增加触发次数
        BatchResult b2 = h.service().submit(completenessBatch("b2", false));
        AlertStatus s2 = h.alertService().status("ds", "a-comp").orElseThrow();
        assertEquals(AlertState.FIRING, s2.state());
        assertEquals(1L, s2.firedCount(), "重复告警必须合并，firedCount 不增长");
        assertTrue(s2.events().get(1).message().startsWith("SUPPRESSED"));

        // 3) 恢复：关闭
        h.service().submit(completenessBatch("b3", true));
        AlertStatus s3 = h.alertService().status("ds", "a-comp").orElseThrow();
        assertEquals(AlertState.RESOLVED, s3.state());

        // 4) 恢复后再次越界：重新打开
        h.service().submit(completenessBatch("b4", false));
        AlertStatus s4 = h.alertService().status("ds", "a-comp").orElseThrow();
        assertEquals(AlertState.FIRING, s4.state());
        assertEquals(2L, s4.firedCount(), "恢复后再越界必须重新打开，firedCount 增长");
        assertTrue(s4.events().get(3).message().startsWith("REOPENED"));

        log.info("[测试断言] 告警状态流转: {} -> {} -> {} -> {}，事件序列={}",
                s1.state(), s2.state(), s3.state(), s4.state(),
                s4.events().stream().map(e -> e.message().split(":")[0]).toList());
    }

    @Test
    void thresholdChangeKeepsHistoryReproducible() {
        TestFixture.Harness h = TestFixture.newService("2026-09-24T10:00:00Z");
        h.service().registerContract("ds",
                List.of(new FieldSpec("id", FieldType.STRING, true)),
                List.of(new QualityRule("r-complete", RuleType.COMPLETENESS, "id", Map.of("min", 1.0))));
        h.service().registerAlert("a-comp", "ds", MetricName.COMPLETENESS_RATIO, "<", 0.95);

        // 历史批次 b1 完整率 0.5，在旧阈值 0.95 下越界 -> FIRING，事件固化 ruleVersion=1
        h.service().submit(completenessBatch("b1", false));
        AlertStatus before = h.alertService().status("ds", "a-comp").orElseThrow();
        assertEquals(AlertState.FIRING, before.state());
        assertEquals(1, before.events().get(0).ruleVersion());

        // 阈值收紧到 0.4：0.5 不再越界 -> RESOLVED，事件记录新版本号 2
        h.service().registerAlert("a-comp", "ds", MetricName.COMPLETENESS_RATIO, "<", 0.4);
        h.service().submit(completenessBatch("b2", false));
        AlertStatus after = h.alertService().status("ds", "a-comp").orElseThrow();
        assertEquals(AlertState.RESOLVED, after.state());
        assertEquals(2, after.events().get(1).ruleVersion(), "阈值变更生成新版本");

        // 历史事件不可变：b1 的 OPENED 事件仍为 v1 且状态为 FIRING 记录
        var openedEvent = after.events().get(0);
        assertEquals(1, openedEvent.ruleVersion());
        assertEquals(AlertState.FIRING, openedEvent.state());
        assertEquals(0.5, openedEvent.observedValue(), 1e-9);

        // 历史批次结论本身也可复现：状态、契约版本与规则判定不变
        BatchResult storedB1 = h.service().getBatch("ds", "b1");
        assertEquals(0.5, storedB1.verdicts().get(0).observed(), 1e-9);
        log.info("[测试断言] 阈值 0.95->0.4 后，历史事件保持 v1={}，新事件为 v2={}，历史批次结论不变",
                openedEvent.message(), after.events().get(1).message());
    }

    @Test
    void okStateWhenNeverBreaching() {
        TestFixture.Harness h = TestFixture.newService("2026-09-24T10:00:00Z");
        h.service().registerContract("ds",
                List.of(new FieldSpec("id", FieldType.STRING, true)),
                List.of(new QualityRule("r-complete", RuleType.COMPLETENESS, "id", Map.of("min", 1.0))));
        h.service().registerAlert("a-comp", "ds", MetricName.COMPLETENESS_RATIO, "<", 0.95);
        h.service().submit(completenessBatch("b1", true));
        AlertStatus status = h.alertService().status("ds", "a-comp").orElseThrow();
        assertEquals(AlertState.OK, status.state());
        assertTrue(status.events().get(0).message().startsWith("OK"));
        log.info("[测试断言] 从未越界时告警保持 OK");
    }
}
