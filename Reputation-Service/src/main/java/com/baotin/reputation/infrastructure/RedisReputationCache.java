package com.baotin.reputation.infrastructure;

import com.baotin.reputation.api.ReputationResponse;
import com.baotin.reputation.application.ReputationCache;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

public class RedisReputationCache implements ReputationCache {
    private static final Logger log = LoggerFactory.getLogger(RedisReputationCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    public RedisReputationCache(StringRedisTemplate redis, JsonMapper json, long ttlSeconds) {
        this.redis = redis;
        this.json = json;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    @Override
    public Optional<ReputationResponse> get(UUID targetId) {
        try {
            String raw = redis.opsForValue().get(key(targetId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(json.readValue(raw, ReputationResponse.class));
        } catch (RuntimeException e) {
            log.warn("Reputation cache read failed for {}: {}", targetId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID targetId, ReputationResponse response) {
        try {
            redis.opsForValue().set(key(targetId), json.writeValueAsString(response), ttl);
        } catch (RuntimeException e) {
            log.warn("Reputation cache write failed for {}: {}", targetId, e.getClass().getSimpleName());
        }
    }

    @Override
    public void evict(UUID targetId) {
        try {
            redis.delete(key(targetId));
        } catch (RuntimeException e) {
            log.warn("Reputation cache eviction failed for {}: {}", targetId, e.getClass().getSimpleName());
        }
    }

    private static String key(UUID targetId) {
        return "reputation:target:" + targetId;
    }
}
