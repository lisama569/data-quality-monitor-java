package com.github.lisama569.dataqualitymonitorjava.store;

import com.github.lisama569.dataqualitymonitorjava.common.MetricName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 指标时序样本存储（内存模拟）。
 * 以 dataset|metric 维护时间序列；batchId+metric 维度幂等去重，
 * 重复提交批次不会产生第二条样本，指标不会重复累计。
 */
@Component
public class MetricsStore {

    public record MetricSample(String dataset, MetricName metric, String batchId, double value, long timestamp) {
    }

    private final Map<String, List<MetricSample>> series = new ConcurrentHashMap<>();
    private final Set<String> recordedBatches = ConcurrentHashMap.newKeySet();

    private static String seriesKey(String dataset, MetricName metric) {
        return dataset + "|" + metric.name();
    }

    private static String dedupeKey(String dataset, String batchId, MetricName metric) {
        return dataset + "|" + batchId + "|" + metric.name();
    }

    /** 同一 batchId+metric 幂等写入；返回 true 表示这是新样本。 */
    public boolean recordIfAbsent(String batchId, MetricName metric, String dataset, double value, long timestamp) {
        if (!recordedBatches.add(dedupeKey(dataset, batchId, metric))) {
            return false;
        }
        List<MetricSample> list = series.computeIfAbsent(seriesKey(dataset, metric),
                k -> new ArrayList<>());
        synchronized (list) {
            list.add(new MetricSample(dataset, metric, batchId, value, timestamp));
            list.sort((a, b) -> Long.compare(a.timestamp(), b.timestamp()));
        }
        return true;
    }

    public List<MetricSample> series(String dataset, MetricName metric) {
        List<MetricSample> list = series.get(seriesKey(dataset, metric));
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    public Optional<MetricSample> latest(String dataset, MetricName metric) {
        List<MetricSample> list = series.get(seriesKey(dataset, metric));
        if (list == null) {
            return Optional.empty();
        }
        synchronized (list) {
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
        }
    }
}
