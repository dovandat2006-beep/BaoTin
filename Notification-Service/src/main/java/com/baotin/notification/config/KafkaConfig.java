package com.baotin.notification.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "notification.events.enabled", havingValue = "true")
public class KafkaConfig {
    private static final Set<String> PROTOCOLS = Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");

    @Bean(destroyMethod = "close")
    Producer<String, String> outboxProducer(
        @Value("${notification.events.bootstrap-servers}") String servers,
        @Value("${notification.events.security-protocol}") String protocol,
        @Value("${notification.events.sasl-mechanism:}") String mechanism,
        @Value("${notification.events.sasl-jaas-config:}") String jaas) {
        Map<String, Object> config = base(servers, protocol, mechanism, jaas);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "notification-outbox");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        return new KafkaProducer<>(config);
    }

    @Bean(destroyMethod = "close")
    Producer<String, String> deadLetterProducer(
        @Value("${notification.events.bootstrap-servers}") String servers,
        @Value("${notification.events.security-protocol}") String protocol,
        @Value("${notification.events.sasl-mechanism:}") String mechanism,
        @Value("${notification.events.sasl-jaas-config:}") String jaas) {
        Map<String, Object> config = base(servers, protocol, mechanism, jaas);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "notification-dlt");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        return new KafkaProducer<>(config);
    }

    @Bean(destroyMethod = "close")
    Consumer<String, String> eventsConsumer(
        @Value("${notification.events.bootstrap-servers}") String servers,
        @Value("${notification.events.security-protocol}") String protocol,
        @Value("${notification.events.sasl-mechanism:}") String mechanism,
        @Value("${notification.events.sasl-jaas-config:}") String jaas,
        @Value("${notification.events.group-id}") String groupId) {
        Map<String, Object> config = base(servers, protocol, mechanism, jaas);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "notification-events");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return new KafkaConsumer<>(config);
    }

    private static Map<String, Object> base(String servers, String protocol, String mechanism, String jaas) {
        String normalized = protocol == null ? "" : protocol.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PROTOCOLS.contains(normalized)) {
            throw new IllegalStateException("Unsupported Kafka security protocol: " + protocol);
        }
        if (servers == null || servers.isBlank()) {
            throw new IllegalStateException("Kafka bootstrap servers are required when eventing is enabled");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, servers.trim());
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, normalized);
        if (normalized.startsWith("SASL")) {
            if (mechanism == null || mechanism.isBlank() || jaas == null || jaas.isBlank()) {
                throw new IllegalStateException("SASL mechanism and JAAS config are required for " + normalized);
            }
            config.put("sasl.mechanism", mechanism.trim());
            config.put("sasl.jaas.config", jaas);
        }
        return config;
    }
}
