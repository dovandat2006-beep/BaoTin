package com.baotin.reputation.infrastructure;

import com.baotin.reputation.application.ReputationCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class CacheConfig {
    @Bean
    @ConditionalOnProperty(name = "reputation.cache.enabled", havingValue = "true")
    ReputationCache redisReputationCache(StringRedisTemplate redis, JsonMapper json,
                                         @Value("${reputation.cache.ttl-seconds:300}") long ttlSeconds) {
        return new RedisReputationCache(redis, json, ttlSeconds);
    }

    @Bean
    @ConditionalOnProperty(name = "reputation.cache.enabled", havingValue = "false", matchIfMissing = true)
    ReputationCache noOpReputationCache() {
        return new NoOpReputationCache();
    }
}
