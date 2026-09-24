package com.github.lisama569.dataqualitymonitorjava.model;

/** 可区分的拒绝原因：批次因此不会被评估、指标也不会累计。 */
public enum RejectReason {
    CONTRACT_NOT_FOUND("数据契约不存在"),
    SCHEMA_MISSING_COLUMN("批次缺少契约声明的字段（结构漂移）"),
    SCHEMA_UNEXPECTED_COLUMN("批次出现契约未声明的字段（结构漂移）"),
    ILLEGAL_RULE("规则配置非法"),
    UNKNOWN_METRIC("未知指标/规则类型"),
    DUPLICATE_ID_CONTENT_CONFLICT("批次标识已存在但提交内容不同，拒绝覆盖既有结果"),
    EMPTY_BATCH("批次没有任何数据行");

    private final String defaultMessage;

    RejectReason(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
