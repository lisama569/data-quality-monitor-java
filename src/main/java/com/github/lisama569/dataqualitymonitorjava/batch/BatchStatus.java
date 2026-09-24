package com.github.lisama569.dataqualitymonitorjava.batch;

/** 批次评估结论。REJECTED 表示未被接受进入评估流程；FAILED 表示已评估但存在违规规则。 */
public enum BatchStatus {
    PASSED,
    FAILED,
    REJECTED
}
