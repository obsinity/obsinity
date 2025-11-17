package com.obsinity.service.core.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
