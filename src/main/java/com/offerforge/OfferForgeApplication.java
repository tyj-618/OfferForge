package com.offerforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
// 掌握度周衰减等定时任务
@EnableScheduling
public class OfferForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfferForgeApplication.class, args);
    }
}
