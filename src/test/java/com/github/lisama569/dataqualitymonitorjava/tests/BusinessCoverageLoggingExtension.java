package com.github.lisama569.dataqualitymonitorjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务覆盖日志扩展：在单测日志中明确打印“覆盖了哪个业务（测试类）”，
 * 以及该业务下每条用例（方法 + @DisplayName 业务场景名）的开始、通过/失败/跳过，
 * 并在业务全部跑完后输出用例总数与通过情况，便于一眼看出各业务的用例与测试情况。
 *
 * <p>注意 JUnit5 中 {@link TestWatcher} 的每条用例回调与 beforeAll/afterAll 处在不同
 * ExtensionContext 上，因此这里使用按测试类聚合的字段计数器（本扩展由 JUnit 每类实例化一次）。
 */
public class BusinessCoverageLoggingExtension implements BeforeAllCallback, AfterAllCallback, TestWatcher {

    private static final class Counters {
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger passed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
    }

    private final Counters counters = new Counters();

    private Logger log(ExtensionContext context) {
        return LoggerFactory.getLogger(context.getRequiredTestClass());
    }

    private String business(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName();
    }

    private String caseName(ExtensionContext context) {
        String display = context.getDisplayName();
        String method = context.getTestMethod().map(m -> m.getName() + "()").orElse("?");
        return method + " [" + display + "]";
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        log(context).info("========== 业务覆盖开始：业务=[{}] ==========", business(context));
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        int total = counters.total.incrementAndGet();
        counters.passed.incrementAndGet();
        log(context).info("[业务用例通过] 业务={} 序号={} 用例={}",
                business(context), total, caseName(context));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        int total = counters.total.incrementAndGet();
        counters.failed.incrementAndGet();
        log(context).error("[业务用例失败] 业务={} 序号={} 用例={} 原因={}",
                business(context), total, caseName(context),
                cause == null ? "(无异常信息)" : cause.toString());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        int total = counters.total.incrementAndGet();
        counters.skipped.incrementAndGet();
        log(context).warn("[业务用例中止] 业务={} 序号={} 用例={}",
                business(context), total, caseName(context));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        int total = counters.total.incrementAndGet();
        counters.skipped.incrementAndGet();
        log(context).warn("[业务用例跳过] 业务={} 序号={} 用例={} 原因={}",
                business(context), total, caseName(context), reason.orElse("(无)"));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        log(context).info("========== 业务覆盖结束：业务=[{}] 用例总数={} 通过={} 失败={} 跳过={} ==========",
                business(context), counters.total.get(), counters.passed.get(),
                counters.failed.get(), counters.skipped.get());
    }
}
