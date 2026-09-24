package com.github.lisama569.dataqualitymonitorjava.monitor;

/**
 * 告警状态流转：OK -> FIRING（越界打开）-> RESOLVED（恢复关闭）；
 * RESOLVED 后再次越界重新打开新的告警（同键合并，不重复产生）。
 */
public enum AlertState {
    OK,
    FIRING,
    RESOLVED
}
