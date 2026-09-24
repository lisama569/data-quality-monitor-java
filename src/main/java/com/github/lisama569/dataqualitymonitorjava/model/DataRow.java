package com.github.lisama569.dataqualitymonitorjava.model;

import java.util.Map;

/** 批次中的一行数据，值统一以字符串承载，评估时按契约类型解析。 */
public record DataRow(Map<String, String> values) {
}
