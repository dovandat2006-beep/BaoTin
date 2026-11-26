package com.baotin.threat.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** At least once: broker ack precedes DB commit. Consumers deduplicate by eventId. */
@Component
@ConditionalOnProperty(name = "scan.events-enabled", havingValue = "true")
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KafkaProducer<String, String> producer;
    private final MeterRegistry metrics;
    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate transactions, MeterRegistry metrics,
                           @Value("${scan.kafka-bootstrap-servers}") String bootstrap) {
        this.jdbc = jdbc; this.transactions = transactions; this.metrics = metrics;
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all"); properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000); properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000); properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        producer = new KafkaProducer<>(properties);
    }
    @Scheduled(fixedDelayString = "${scan.outbox-interval-ms:1000}")
    public void publish() {
        transactions.executeWithoutResult(status -> {
            var pending = jdbc.queryForList("SELECT id,topic,message_key,payload::text,attempts FROM threat.outbox_events WHERE status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=now()) ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED");
            for (var row : pending) {
                UUID id = (UUID) row.get("id"); int attempt = ((Number) row.get("attempts")).intValue() + 1;
                try {
                    producer.send(new ProducerRecord<>((String) row.get("topic"), (String) row.get("message_key"), (String) row.get("payload"))).get(4, TimeUnit.SECONDS);
                    jdbc.update("UPDATE threat.outbox_events SET status='PUBLISHED',published_at=now(),attempts=?,last_error=NULL WHERE id=?", attempt, id);
                    metrics.counter("baotin_outbox_published_total").increment();
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    jdbc.update("UPDATE threat.outbox_events SET attempts=?,status=?,next_attempt_at=now()+(? * interval '1 second'),last_error='BROKER_UNAVAILABLE' WHERE id=?",
                            attempt, attempt >= 10 ? "FAILED" : "PENDING", Math.min(300, 1 << Math.min(attempt, 8)), id);
                    metrics.counter("baotin_outbox_errors_total").increment();
                }
            }
        });
    }
    @PreDestroy void close() { producer.close(java.time.Duration.ofSeconds(2)); }
}
