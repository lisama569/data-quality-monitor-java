package com.github.lisama569.dataqualitymonitorjava.exception;

import com.github.lisama569.dataqualitymonitorjava.model.RejectReason;

import java.util.List;

/**
 * 批次被拒绝（拒绝原因）。此类异常携带可区分的拒绝原因列表，
 * 用于与规则级“失败原因”严格区分。
 */
public class BatchRejectedException extends DataQualityException {

    private final List<RejectReasonEntry> reasons;

    public record RejectReasonEntry(RejectReason reason, String detail) {
    }

    public BatchRejectedException(List<RejectReasonEntry> reasons) {
        super(reasons.isEmpty() ? "批次被拒绝" : reasons.get(0).detail());
        this.reasons = reasons;
    }

    public List<RejectReasonEntry> getReasons() {
        return reasons;
    }
}
