package com.baotin.reputation.config;

import com.baotin.reputation.domain.ReputationCalculator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ReputationCalculator reputationCalculator() {
        return new ReputationCalculator();
    }
}
