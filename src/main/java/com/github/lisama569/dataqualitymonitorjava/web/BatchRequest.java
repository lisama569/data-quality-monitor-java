package com.github.lisama569.dataqualitymonitorjava.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 批次提交请求。businessTime 为数据业务时点（缺省回退 submittedAt）；
 * evaluationTime 为显式判定时刻（缺省取系统当前时间），二者均可选。 */
public record BatchRequest(String batchId, String contractId,
                           Instant submittedAt, Instant businessTime, Instant evaluationTime,
                           List<Map<String, String>> rows) {
}
