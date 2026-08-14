package com.offerforge.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存滑动窗口限流器：窗口内限额、窗口滑动后恢复、key 隔离。
 */
class InMemoryRateLimiterTests {

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

    @Test
    void allowsUpToLimitThenRejects() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("user:1:qa", 5, 60_000L)).as("第 %d 次应放行", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire("user:1:qa", 5, 60_000L)).isFalse();
        assertThat(limiter.tryAcquire("user:1:qa", 5, 60_000L)).isFalse();
    }

    @Test
    void slidingWindowReleasesQuotaAfterElapsed() throws InterruptedException {
        assertThat(limiter.tryAcquire("user:2:qa", 2, 50L)).isTrue();
        assertThat(limiter.tryAcquire("user:2:qa", 2, 50L)).isTrue();
        assertThat(limiter.tryAcquire("user:2:qa", 2, 50L)).isFalse();

        Thread.sleep(80);

        assertThat(limiter.tryAcquire("user:2:qa", 2, 50L)).isTrue();
    }

    @Test
    void keysAreIsolated() {
        assertThat(limiter.tryAcquire("user:3:qa", 1, 60_000L)).isTrue();
        assertThat(limiter.tryAcquire("user:3:qa", 1, 60_000L)).isFalse();
        // 不同用户/路由互不影响
        assertThat(limiter.tryAcquire("user:4:qa", 1, 60_000L)).isTrue();
        assertThat(limiter.tryAcquire("user:3:report", 1, 60_000L)).isTrue();
    }
}
