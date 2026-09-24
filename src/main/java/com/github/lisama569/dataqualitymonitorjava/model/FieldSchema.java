package com.github.lisama569.dataqualitymonitorjava.model;

/** 数据契约中的字段声明。 */
public record FieldSchema(String name, DataType dataType, boolean required) {
}
