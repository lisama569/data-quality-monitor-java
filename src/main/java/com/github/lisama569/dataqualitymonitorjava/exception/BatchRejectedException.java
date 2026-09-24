package com.github.lisama569.dataqualitymonitorjava.exception;

import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;

/** 批次被拒绝（未进入评估流程）。HTTP 映射：重复冲突 409，其余 422。 */
public class BatchRejectedException extends RuntimeException {

    private final RejectReason reason;

    public BatchRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
