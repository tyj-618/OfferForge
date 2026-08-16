package com.offerforge.quota;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 额度模块 Bean 装配：系统时钟单独成 Bean，便于测试注入固定时钟穿越日期边界。
 */
@Configuration
public class QuotaConfiguration {

    @Bean
    public Clock quotaClock() {
        return Clock.systemDefaultZone();
    }
}
