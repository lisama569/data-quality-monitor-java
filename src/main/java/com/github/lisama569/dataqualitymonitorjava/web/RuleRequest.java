package com.github.lisama569.dataqualitymonitorjava.web;

import java.util.Set;

/** 规则登记/更新请求（归属契约，新版本自动递增）。 */
public record RuleRequest(String contractId, String ruleId, String type, String fieldName,
                          Double threshold, Set<String> allowedValues,
                          Long numericMin, Long numericMax, Long maxAgeSeconds) {
}
