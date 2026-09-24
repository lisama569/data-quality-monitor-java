package com.github.lisama569.dataqualitymonitorjava.engine;

import java.util.List;

/** 结构校验结果。漂移时携带缺失字段与未知字段明细。 */
public record SchemaValidationResult(boolean compatible, List<String> missingRequiredFields, List<String> unknownFields) {
}
