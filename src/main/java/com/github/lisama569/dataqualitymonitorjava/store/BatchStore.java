package com.github.lisama569.dataqualitymonitorjava.store;

import com.github.lisama569.dataqualitymonitorjava.batch.BatchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 批次结果存储（内存模拟本地存储）。
 * key = dataset + "|" + batchId，putIfAbsent 以 synchronized 提供原子占位语义，
 * 保证并发重复提交时只有第一个提交写入，其余提交拿到同一既有结果：指标不重复累计、结论不漂移。
 */
@Component
public class BatchStore {

    private final Map<String, BatchResult> results = new ConcurrentHashMap<>();

    private static String key(String dataset, String batchId) {
        return dataset + "|" + batchId;
    }

    /** 原子写入：key 不存在时写入并返回空；已存在则不覆盖，返回既有结果。 */
    public synchronized Optional<BatchResult> putIfAbsent(BatchResult result) {
        BatchResult existing = results.putIfAbsent(key(result.dataset(), result.batchId()), result);
        return Optional.ofNullable(existing);
    }

    public synchronized Optional<BatchResult> get(String dataset, String batchId) {
        return Optional.ofNullable(results.get(key(dataset, batchId)));
    }

    public synchronized List<BatchResult> findByDataset(String dataset) {
        List<BatchResult> list = new ArrayList<>();
        for (BatchResult result : results.values()) {
            if (result.dataset().equals(dataset)) {
                list.add(result);
            }
        }
        return List.copyOf(list);
    }
}
