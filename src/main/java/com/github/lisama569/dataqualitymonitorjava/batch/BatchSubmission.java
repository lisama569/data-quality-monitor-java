package com.github.lisama569.dataqualitymonitorjava.batch;

import java.util.List;
import java.util.Map;

/**
 * 批次提交入参。batchId 为调用方提供的幂等键；
 * rows 为有序记录列表（List 保证序列化哈希稳定）；
 * eventTime 为该批次数据的业务时间（epochMilli），用于新鲜度评估。
 */
public record BatchSubmission(String dataset,
                              String batchId,
                              List<Map<String, Object>> rows,
                              Long eventTime) {
}
