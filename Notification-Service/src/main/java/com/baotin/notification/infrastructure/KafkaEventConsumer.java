package com.baotin.notification.infrastructure;

import com.baotin.notification.application.EventIngestor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * At-least-once consumer with an inbox dedupe. Poison records go to a DLT and
 * are only committed after the DLT write is acknowledged.
 */
@Component
@ConditionalOnProperty(name = "notification.events.enabled", havingValue = "true")
public class KafkaEventConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);

    private final Consumer<String, String> consumer;
    private final Producer<String, String> deadLetters;
    private final EventIngestor ingestor;
    private final List<String> topics;
    private final long pollMillis;
    private volatile boolean running;
    private Thread worker;

    public KafkaEventConsumer(Consumer<String, String> consumer,
                              @Qualifier("deadLetterProducer") Producer<String, String> deadLetters,
                              EventIngestor ingestor,
                              @Value("${notification.events.reputation-topic}") String reputationTopic,
                              @Value("${notification.events.report-topic}") String reportTopic,
                              @Value("${notification.events.poll-ms:500}") long pollMillis) {
        if (reputationTopic.isBlank() || reportTopic.isBlank()) {
            throw new IllegalStateException("Event topics must not be blank");
        }
        this.consumer = consumer;
        this.deadLetters = deadLetters;
        this.ingestor = ingestor;
        this.topics = List.of(reputationTopic, reportTopic);
        this.pollMillis = pollMillis;
    }

    @Override public void start() {
        if (running) return;
        running = true;
        worker = Thread.ofPlatform().name("notification-events").daemon(true).start(this::loop);
    }

    @Override public void stop() {
        running = false;
        consumer.wakeup();
        if (worker != null) {
            try {
                worker.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }

    private void loop() {
        consumer.subscribe(topics);
        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(pollMillis));
                for (ConsumerRecord<String, String> record : records) {
                    if (!handle(record)) {
                        consumer.seek(new TopicPartition(record.topic(), record.partition()), record.offset());
                        break;
                    }
                }
                if (!records.isEmpty()) consumer.commitSync();
            }
        } catch (WakeupException e) {
            if (running) throw e;
        } catch (Exception e) {
            log.error("Kafka event consumer stopped unexpectedly", e);
        } finally {
            try {
                consumer.close(Duration.ofSeconds(5));
            } catch (Exception ignored) {
                // shutdown
            }
        }
    }

    /**
     * @return true when the record is acknowledged (processed or dead-lettered),
     *         false when the broker/DLT is unavailable and the offset must not advance.
     */
    private boolean handle(ConsumerRecord<String, String> record) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                ingestor.ingest(record.value());
                return true;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < 3) sleep(200L * attempt);
            }
        }
        return deadLetter(record, last);
    }

    private boolean deadLetter(ConsumerRecord<String, String> record, RuntimeException error) {
        String topic = record.topic() + ".DLT";
        var out = new ProducerRecord<String, String>(topic, record.key(), record.value());
        out.headers().add("x-original-topic", record.topic().getBytes(StandardCharsets.UTF_8));
        out.headers().add("x-error", (error == null ? "unknown" : error.getClass().getSimpleName()).getBytes(StandardCharsets.UTF_8));
        try {
            deadLetters.send(out).get(10, java.util.concurrent.TimeUnit.SECONDS);
            log.warn("Event from {} offset {} dead-lettered: {}", record.topic(), record.offset(),
                error == null ? "unknown" : error.getMessage());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Dead-letter interrupted for event from {} offset {}", record.topic(), record.offset());
            return false;
        } catch (Exception e) {
            log.error("Could not dead-letter event from {} offset {}", record.topic(), record.offset());
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
