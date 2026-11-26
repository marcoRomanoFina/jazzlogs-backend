package com.jazzlogs.backend.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's notion of "now", as a bean so that code whose behaviour
 * depends on the time (MessageLimitService's windows) can be tested with a
 * fixed clock instead of waiting for real time to pass.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
