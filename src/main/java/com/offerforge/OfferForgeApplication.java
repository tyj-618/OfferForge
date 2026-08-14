package com.offerforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OfferForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfferForgeApplication.class, args);
    }
}
