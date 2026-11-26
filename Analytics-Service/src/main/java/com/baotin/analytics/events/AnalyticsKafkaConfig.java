package com.baotin.analytics.events;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class AnalyticsKafkaConfig {

    @Bean
    DefaultErrorHandler analyticsKafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Map.of(), true);
        return handler;
    }

    @Bean
    DefaultKafkaConsumerFactoryCustomizer analyticsKafkaSecurityCustomizer(
            @Value("${analytics.events.security-protocol:}") String securityProtocol,
            @Value("${analytics.events.sasl-mechanism:}") String saslMechanism,
            @Value("${analytics.events.sasl-jaas-config:}") String saslJaasConfig) {
        return consumerFactory -> {
            Map<String, Object> overrides = new LinkedHashMap<>();
            if (securityProtocol != null && !securityProtocol.isBlank()) {
                overrides.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol.trim());
            }
            if (saslMechanism != null && !saslMechanism.isBlank()) {
                overrides.put("sasl.mechanism", saslMechanism.trim());
            }
            if (saslJaasConfig != null && !saslJaasConfig.isBlank()) {
                overrides.put("sasl.jaas.config", saslJaasConfig.trim());
            }
            if (!overrides.isEmpty()) {
                consumerFactory.updateConfigs(overrides);
            }
        };
    }
}
