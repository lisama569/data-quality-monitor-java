package com.github.lisama569.dataqualitymonitorjava.common;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 统一时间来源，测试可替换为固定时钟，保证新鲜度与告警时序结论可复现。 */
@Component
public class TimeService {

    private final Clock clock;

    public TimeService(Clock clock) {
        this.clock = clock;
    }

    public long nowMillis() {
        return clock.millis();
    }

    public Instant now() {
        return clock.instant();
    }
}
