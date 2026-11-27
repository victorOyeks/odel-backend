package com.odel.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OdelProperties.class)
@EnableScheduling
public class AppConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
