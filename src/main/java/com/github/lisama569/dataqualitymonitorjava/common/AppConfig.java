package com.github.lisama569.dataqualitymonitorjava.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 本地 Bean 配置：默认系统 UTC 时钟，测试中可注入固定时钟。 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
