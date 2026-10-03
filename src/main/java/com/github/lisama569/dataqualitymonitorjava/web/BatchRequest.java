package com.github.lisama569.dataqualitymonitorjava.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 批次提交请求。
 * submittedAt 为批次到达/提交时间（缺省取服务端当前时间）；
 * businessTime 为数据自身的业务时点（缺省回退 submittedAt），新鲜度按它到判定时刻的滞后计算。
 */
public record BatchRequest(String batchId, String contractId,
                           Instant submittedAt, Instant businessTime,
                           List<Map<String, String>> rows) {
}
