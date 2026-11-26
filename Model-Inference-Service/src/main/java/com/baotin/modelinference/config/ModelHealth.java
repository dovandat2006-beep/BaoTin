package com.baotin.modelinference.config;

import com.baotin.modelinference.application.ModelRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("models")
public class ModelHealth implements HealthIndicator {
    private final ModelRegistry models;
    private final String token;
    public ModelHealth(ModelRegistry models, @Value("${model.service-token:}") String token) { this.models = models; this.token = token; }
    @Override public Health health() { return (models.ready() && token.length() >= 32 ? Health.up() : Health.down()).build(); }
}
