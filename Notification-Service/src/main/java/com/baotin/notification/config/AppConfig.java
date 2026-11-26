package com.baotin.notification.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableScheduling
public class AppConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
}
