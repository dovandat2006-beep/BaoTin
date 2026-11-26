package com.baotin.search;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

@Component
@ConditionalOnProperty(name="search.events.enabled",havingValue="true",matchIfMissing=true)
class SearchEvents {
    private final Projection projection;
    SearchEvents(Projection projection) { this.projection=projection; }
    @KafkaListener(id="search-projection", idIsGroup=false, topics={"baotin.search.targets.v1","baotin.reputation.events.v1","baotin.threat.search.events.v1","baotin.campaign.search.events.v1"})
    void receive(ConsumerRecord<String,String> record) { projection.ingest(record.topic(),record.value()); }
}
@Configuration
class SearchEventConfig {
    @Bean DefaultErrorHandler projectionErrorHandler() {
        var handler=new DefaultErrorHandler(new FixedBackOff(5000L,FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(),true); // Validation errors must not be acknowledged either.
        return handler;
    }
    @Bean HealthIndicator projectionHealthIndicator(KafkaListenerEndpointRegistry registry, org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Value("${search.events.enabled}") boolean enabled) {
        return () -> {
            try { jdbc.queryForList("SELECT id,title,risk_score,risk_level,updated_at FROM search.search_documents LIMIT 0"); jdbc.queryForList("SELECT sequence FROM search.projection_journal LIMIT 0"); }
            catch (org.springframework.dao.DataAccessException ignored) { return Health.down().build(); }
            var listener=registry.getListenerContainer("search-projection");
            boolean ready=!enabled || listener!=null && listener.isRunning() && listener.getAssignedPartitions()!=null
                && !listener.getAssignedPartitions().isEmpty();
            return (ready ? Health.up() : Health.down()).build();
        };
    }
}
