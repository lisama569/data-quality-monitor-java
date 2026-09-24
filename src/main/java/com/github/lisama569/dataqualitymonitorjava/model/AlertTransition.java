package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;

/** 告警状态流转记录，保证流转可追溯、可复现。 */
public record AlertTransition(Instant at, String batchId, AlertState from, AlertState to, String note) {
}
