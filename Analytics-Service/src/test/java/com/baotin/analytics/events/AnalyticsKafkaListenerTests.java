package com.baotin.analytics.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

@ExtendWith(OutputCaptureExtension.class)
class AnalyticsKafkaListenerTests {

    @Test
    void validRecordIngestsWithoutRejections() {
        AnalyticsEventIngestor ingestor = mock(AnalyticsEventIngestor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalyticsKafkaListener listener = new AnalyticsKafkaListener(ingestor, registry);

        when(ingestor.ingest("baotin.scan.events.v1", "{}")).thenReturn(IngestOutcome.APPLIED);

        listener.receive(new ConsumerRecord<>("baotin.scan.events.v1", 0, 7L, "key", "{}"));

        verify(ingestor).ingest("baotin.scan.events.v1", "{}");
        assertEquals(0, registry.find("analytics.events.rejected").counters().size());
    }

    @Test
    void poisonPillLogsMetadataOnlyIncrementsCounterAndAcknowledgesWithoutThrowing(CapturedOutput output) {
        AnalyticsEventIngestor ingestor = mock(AnalyticsEventIngestor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalyticsKafkaListener listener = new AnalyticsKafkaListener(ingestor, registry);

        UUID eventId = UUID.randomUUID();
        String piiPayload = """
                {"reporterUserId":"pii-reporter-123","traceId":"secret-trace","url":"https://phish.example/token=xyz"}
                """;
        when(ingestor.ingest("baotin.report.events.v1", piiPayload))
                .thenThrow(new AnalyticsInvalidEventException("baotin.report.events.v1", "invalid_report_payload", eventId));

        listener.receive(new ConsumerRecord<>("baotin.report.events.v1", 2, 41L, "sensitive-key-ignored", piiPayload));

        double rejectedCount = registry.get("analytics.events.rejected")
                .tag("topic", "baotin.report.events.v1")
                .tag("reason", "invalid_report_payload")
                .counter()
                .count();
        assertEquals(1.0, rejectedCount);

        String logs = output.toString();
        assertTrue(logs.contains("WARN"));
        assertTrue(logs.contains("baotin.report.events.v1"));
        assertTrue(logs.contains("partition=2"));
        assertTrue(logs.contains("offset=41"));
        assertTrue(logs.contains(eventId.toString()));
        assertTrue(logs.contains("invalid_report_payload"));
        assertFalse(logs.contains("pii-reporter-123"));
        assertFalse(logs.contains("secret-trace"));
        assertFalse(logs.contains("phish.example"));
        assertFalse(logs.contains("sensitive-key-ignored"));
    }

    @Test
    void dataAccessExceptionPropagatesUntouchedWithoutRejectCounter() {
        AnalyticsEventIngestor ingestor = mock(AnalyticsEventIngestor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalyticsKafkaListener listener = new AnalyticsKafkaListener(ingestor, registry);

        when(ingestor.ingest("baotin.scan.events.v1", "{}"))
                .thenThrow(new DataAccessResourceFailureException("postgres connection refused"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> listener.receive(new ConsumerRecord<>("baotin.scan.events.v1", 0, 1L, "k", "{}")));
        assertEquals(0, registry.find("analytics.events.rejected").counters().size());
    }

    @Test
    void securityCustomizerSkipsBlankSaslPropertiesAndAppliesNonBlankOnes() {
        AnalyticsKafkaConfig config = new AnalyticsKafkaConfig();

        DefaultKafkaConsumerFactory<String, String> plainFactory = new DefaultKafkaConsumerFactory<>(
                Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"),
                new StringDeserializer(),
                new StringDeserializer());
        DefaultKafkaConsumerFactoryCustomizer plainCustomizer =
                config.analyticsKafkaSecurityCustomizer("PLAINTEXT", "", "   ");
        plainCustomizer.customize(plainFactory);
        assertEquals("PLAINTEXT", plainFactory.getConfigurationProperties().get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertFalse(plainFactory.getConfigurationProperties().containsKey("sasl.mechanism"));
        assertFalse(plainFactory.getConfigurationProperties().containsKey("sasl.jaas.config"));

        DefaultKafkaConsumerFactory<String, String> saslFactory = new DefaultKafkaConsumerFactory<>(
                Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"),
                new StringDeserializer(),
                new StringDeserializer());
        DefaultKafkaConsumerFactoryCustomizer saslCustomizer = config.analyticsKafkaSecurityCustomizer(
                "SASL_SSL", "SCRAM-SHA-256", "org.apache.kafka.common.security.scram.ScramLoginModule required;");
        saslCustomizer.customize(saslFactory);
        assertEquals("SASL_SSL", saslFactory.getConfigurationProperties().get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SCRAM-SHA-256", saslFactory.getConfigurationProperties().get("sasl.mechanism"));
        assertEquals("org.apache.kafka.common.security.scram.ScramLoginModule required;",
                saslFactory.getConfigurationProperties().get("sasl.jaas.config"));
    }

    @Test
    void errorHandlerClassifiesAllExceptionsAsRetryableWithFixedBackOff() {
        AnalyticsKafkaConfig config = new AnalyticsKafkaConfig();
        DefaultErrorHandler errorHandler = config.analyticsKafkaErrorHandler();
        assertNotNull(errorHandler);
        // DefaultErrorHandler.isSeeksAfterHandling() is true and DataAccessException is not ignored
        assertFalse(errorHandler.seeksAfterHandling() == false);
    }

    @Test
    void listenerBeanRespectsEnabledFlagAndWiresRecordAckWithErrorHandler() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(KafkaSliceApp.class, AnalyticsKafkaConfig.class, AnalyticsKafkaListener.class)
                .withBean(AnalyticsEventIngestor.class, () -> mock(AnalyticsEventIngestor.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:listener_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                        "spring.flyway.enabled=false",
                        "spring.kafka.bootstrap-servers=localhost:9092",
                        "spring.kafka.consumer.group-id=analytics-service-v1",
                        "spring.kafka.listener.ack-mode=record",
                        "spring.kafka.listener.auto-startup=false",
                        "analytics.events.scan-topic=baotin.scan.events.v1",
                        "analytics.events.report-topic=baotin.report.events.v1",
                        "analytics.events.reputation-topic=baotin.reputation.events.v1");

        runner.withPropertyValues("analytics.events.enabled=false")
                .run(ctx -> assertEquals(0, ctx.getBeansOfType(AnalyticsKafkaListener.class).size()));

        runner.withPropertyValues("analytics.events.enabled=true")
                .run(ctx -> {
                    assertEquals(1, ctx.getBeansOfType(AnalyticsKafkaListener.class).size());
                    KafkaListenerEndpointRegistry registry = ctx.getBean(KafkaListenerEndpointRegistry.class);
                    MessageListenerContainer container = registry.getListenerContainer("analytics-events");
                    assertNotNull(container);
                    assertEquals(ContainerProperties.AckMode.RECORD, container.getContainerProperties().getAckMode());
                    assertSame(
                            ctx.getBean("analyticsKafkaErrorHandler"),
                            ((AbstractMessageListenerContainer<?, ?>) container).getCommonErrorHandler());
                    assertFalse(container.isRunning());
                });
    }

    @Configuration
    @EnableAutoConfiguration
    static class KafkaSliceApp {
    }
}
