package com.baotin.analytics.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "analytics.events.enabled", havingValue = "true")
public class AnalyticsKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsKafkaListener.class);

    private final AnalyticsEventIngestor ingestor;
    private final MeterRegistry meterRegistry;

    @Autowired
    public AnalyticsKafkaListener(
            AnalyticsEventIngestor ingestor,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(ingestor, meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }

    public AnalyticsKafkaListener(AnalyticsEventIngestor ingestor, MeterRegistry meterRegistry) {
        this.ingestor = Objects.requireNonNull(ingestor, "ingestor must not be null");
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            id = "analytics-events",
            idIsGroup = false,
            topics = {
                    "${analytics.events.scan-topic:baotin.scan.events.v1}",
                    "${analytics.events.report-topic:baotin.report.events.v1}",
                    "${analytics.events.reputation-topic:baotin.reputation.events.v1}"
            })
    public void receive(ConsumerRecord<String, String> record) {
        if (record == null) {
            log.warn("Rejected null analytics Kafka record: topic=unknown reason=null_record");
            incrementRejected("unknown", "null_record");
            return;
        }
        try {
            ingestor.ingest(record.topic(), record.value());
        } catch (AnalyticsInvalidEventException exception) {
            String topic = safeTag(exception.topic(), safeTag(record.topic(), "unknown"));
            String reason = safeTag(exception.reason(), "invalid_event");
            log.warn("Rejected invalid analytics event: topic={} partition={} offset={} eventId={} reason={}",
                    topic,
                    record.partition(),
                    record.offset(),
                    exception.eventId(),
                    reason);
            incrementRejected(topic, reason);
        }
    }

    private void incrementRejected(String topic, String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("analytics.events.rejected", "topic", topic, "reason", reason).increment();
        }
    }

    private static String safeTag(String candidate, String fallback) {
        return (candidate == null || candidate.isBlank()) ? fallback : candidate;
    }
}
