package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.model.BatchResult;
import com.github.lisama569.dataqualitymonitorjava.model.QualityRule;
import com.github.lisama569.dataqualitymonitorjava.model.ReplayBatchView;
import com.github.lisama569.dataqualitymonitorjava.model.ReplayReport;
import com.github.lisama569.dataqualitymonitorjava.model.RuleOutcome;
import com.github.lisama569.dataqualitymonitorjava.model.RuleType;
import com.github.lisama569.dataqualitymonitorjava.storage.LocalStorage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 只读复盘：按调用方指定的判定时刻，对某契约下已落库批次重算新鲜度口径。
 * <ul>
 *   <li>只产出派生查看结果，绝不回写批次结论、告警状态与流转历史；</li>
 *   <li>重算仅依赖批次落库时保存的业务时点与规则快照，是纯函数：
 *       同一 (契约, 判定时刻) 复盘任意次，结果完全一致；</li>
 *   <li>只重算 FRESHNESS 规则：完整性/唯一性/取值域只取决于数据行，与判定时刻无关，
 *       复盘时沿用落库结论，故不出现在复盘明细中；</li>
 *   <li>复杂度为 O(批次总数 × 快照规则数)，单遍流式计算，几百到上千批次也不会堆积中间状态。</li>
 * </ul>
 */
@Component
public class ReplayService {

    private final LocalStorage storage;
    private final QualityEvaluator evaluator;

    public ReplayService(LocalStorage storage, QualityEvaluator evaluator) {
        this.storage = storage;
        this.evaluator = evaluator;
    }

    /** 按指定判定时刻对契约下已评估批次复盘新鲜度。judgedAt 不能为空。 */
    public ReplayReport replay(String contractId, Instant judgedAt) {
        if (contractId == null || contractId.isBlank()) {
            throw new IllegalArgumentException("contractId 不能为空");
        }
        if (judgedAt == null) {
            throw new IllegalArgumentException("复盘判定时刻 judgedAt 不能为空");
        }

        List<ReplayBatchView> views = new ArrayList<>();
        int violating = 0;
        // 已按 evaluatedAt 稳定排序取出，再固定按 (业务时点, batchId) 排序，保证复盘顺序与运行时刻无关。
        List<BatchResult> batches = storage.listBatches().stream()
                .filter(b -> contractId.equals(b.getContractId()))
                .filter(b -> b.getBusinessTime() != null)
                .sorted(Comparator.comparing(BatchResult::getBusinessTime)
                        .thenComparing(BatchResult::getBatchId))
                .toList();

        for (BatchResult batch : batches) {
            List<RuleOutcome> freshnessOutcomes = new ArrayList<>();
            List<String> violatedRuleIds = new ArrayList<>();
            // 用批次落库时的规则版本快照重算，保证复盘口径就是当时的规则版本。
            for (QualityRule snapshot : batch.getRuleSnapshots()) {
                if (snapshot.getType() != RuleType.FRESHNESS) {
                    continue;
                }
                RuleOutcome outcome =
                        evaluator.replayFreshness(batch.getBusinessTime(), snapshot, judgedAt);
                freshnessOutcomes.add(outcome);
                if (!outcome.isPassed()) {
                    violatedRuleIds.add(snapshot.getRuleId());
                }
            }
            if (!violatedRuleIds.isEmpty()) {
                violating++;
            }
            views.add(new ReplayBatchView(batch.getBatchId(), batch.getContractId(),
                    batch.getBusinessTime(), batch.getStatus(), judgedAt,
                    List.copyOf(freshnessOutcomes), List.copyOf(violatedRuleIds)));
        }

        return new ReplayReport(contractId, judgedAt, views.size(), violating, List.copyOf(views));
    }
}
