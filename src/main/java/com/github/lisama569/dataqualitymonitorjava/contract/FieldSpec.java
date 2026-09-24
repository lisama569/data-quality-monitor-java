package com.github.lisama569.dataqualitymonitorjava.contract;

/** 数据契约中的字段定义。 */
public record FieldSpec(String name, FieldType type, boolean required) {
}
