package com.offerforge.quota;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度逻辑：懒重置跨天、边界（第 N 次成功第 N+1 次拒绝）、关闭后不限量。
 */
class QuotaServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant BASE_TIME = Instant.parse("2026-08-16T02:00:00Z");

    private final InMemoryQuotaStore store = new InMemoryQuotaStore();

    private QuotaService service(boolean enabled, int dailyLimit, Clock clock) {
        QuotaProperties properties = new QuotaProperties();
        properties.setEnabled(enabled);
        properties.setDailyLimit(dailyLimit);
        return new QuotaService(store, properties, clock);
    }

    @Test
    void consumeSucceedsWithinLimitAndRejectsAfterExhausted() {
        Clock clock = Clock.fixed(BASE_TIME, ZONE);
        QuotaService quota = service(true, 2, clock);

        assertThat(quota.checkQuota(1L)).isEqualTo(2);
        assertThat(quota.consumeQuota(1L)).isTrue();
        assertThat(quota.checkQuota(1L)).isEqualTo(1);
        // 第 2 次（边界）成功
        assertThat(quota.consumeQuota(1L)).isTrue();
        assertThat(quota.checkQuota(1L)).isZero();
        // 第 3 次被拒
        assertThat(quota.consumeQuota(1L)).isFalse();
        assertThat(quota.checkQuota(1L)).isZero();
    }

    @Test
    void quotaResetsLazilyOnNextDay() {
        Clock day1 = Clock.fixed(BASE_TIME, ZONE);
        QuotaService quota = service(true, 1, day1);
        assertThat(quota.consumeQuota(1L)).isTrue();
        assertThat(quota.consumeQuota(1L)).isFalse();

        // 同服务跨天后：check/consume 自动使用新日期计数器（懒重置）
        QuotaService nextDayQuota = service(true, 1, Clock.fixed(BASE_TIME.plus(1, ChronoUnit.DAYS), ZONE));
        assertThat(nextDayQuota.checkQuota(1L)).isEqualTo(1);
        assertThat(nextDayQuota.consumeQuota(1L)).isTrue();
    }

    @Test
    void quotaIsIndependentPerUser() {
        Clock clock = Clock.fixed(BASE_TIME, ZONE);
        QuotaService quota = service(true, 1, clock);

        assertThat(quota.consumeQuota(1L)).isTrue();
        assertThat(quota.consumeQuota(2L)).isTrue();
        assertThat(quota.consumeQuota(1L)).isFalse();
        assertThat(quota.consumeQuota(2L)).isFalse();
    }

    @Test
    void disabledQuotaNeverBlocksAndReportsFullLimit() {
        QuotaService quota = service(false, 10, Clock.fixed(BASE_TIME, ZONE));

        assertThat(quota.isEnabled()).isFalse();
        assertThat(quota.checkQuota(1L)).isEqualTo(10);
        for (int i = 0; i < 50; i++) {
            assertThat(quota.consumeQuota(1L)).isTrue();
        }
        assertThat(quota.checkQuota(1L)).isEqualTo(10);
    }
}
