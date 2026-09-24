package com.github.lisama569.dataqualitymonitorjava.exception;

import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;

/** 规则/阈值配置非法。 */
public class InvalidRuleException extends RuntimeException {

    private final RejectReason reason;

    public InvalidRuleException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
