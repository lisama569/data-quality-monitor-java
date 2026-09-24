package com.github.lisama569.dataqualitymonitorjava.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 批次提交请求。 */
public record BatchRequest(String batchId, String contractId,
                           Instant submittedAt, List<Map<String, String>> rows) {
}
