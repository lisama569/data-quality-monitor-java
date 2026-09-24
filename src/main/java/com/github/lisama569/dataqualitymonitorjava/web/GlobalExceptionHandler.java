package com.github.lisama569.dataqualitymonitorjava.web;

import com.github.lisama569.dataqualitymonitorjava.common.RejectReason;
import com.github.lisama569.dataqualitymonitorjava.exception.BatchRejectedException;
import com.github.lisama569.dataqualitymonitorjava.exception.InvalidRuleException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 拒绝原因 -> HTTP 状态映射：
 * DUPLICATE_BATCH_CONFLICT（同标识不同内容冲突）为 409；
 * 字段缺失/结构漂移/非法规则/未知指标/未知数据集/空批次为 422。
 * 精确重复提交不抛异常，直接返回 200 的既有结论（duplicate=true 语义在结果中体现）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BatchRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(BatchRejectedException ex) {
        return ResponseEntity.status(status(ex.reason()))
                .body(Map.of("rejected", true, "reason", ex.reason().name(), "message", ex.getMessage()));
    }

    @ExceptionHandler(InvalidRuleException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRule(InvalidRuleException ex) {
        return ResponseEntity.status(status(ex.reason()))
                .body(Map.of("rejected", true, "reason", ex.reason().name(), "message", ex.getMessage()));
    }

    private HttpStatus status(RejectReason reason) {
        return switch (reason) {
            case DUPLICATE_BATCH_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
