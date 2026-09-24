package com.github.lisama569.dataqualitymonitorjava.exception;

/** 数据质量领域异常基类。 */
public abstract class DataQualityException extends RuntimeException {
    protected DataQualityException(String message) {
        super(message);
    }
}
