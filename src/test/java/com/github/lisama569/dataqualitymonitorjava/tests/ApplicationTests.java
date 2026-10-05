package com.github.lisama569.dataqualitymonitorjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 应用上下文冒烟测试。
 */
@SpringBootTest
@ExtendWith(BusinessCoverageLoggingExtension.class)
public class ApplicationTests {
    @Test
    @DisplayName("Spring 应用上下文能正常加载")
    public void contentLoad() {
    }
}
