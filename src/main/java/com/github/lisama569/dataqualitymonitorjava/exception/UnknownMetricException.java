package com.github.lisama569.dataqualitymonitorjava.exception;

/** 未知指标（规则类型代码无法识别），不得静默跳过。 */
public class UnknownMetricException extends DataQualityException {

    private final String unknownCode;

    public UnknownMetricException(String unknownCode) {
        super("未知指标: " + unknownCode);
        this.unknownCode = unknownCode;
    }

    public String getUnknownCode() {
        return unknownCode;
    }
}
