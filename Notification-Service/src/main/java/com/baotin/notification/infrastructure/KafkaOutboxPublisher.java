package com.baotin.notification.infrastructure;

import com.baotin.notification.application.OutboxStore;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Publishes the transactional outbox with a leased claim and bounded retries. */
@Component
@ConditionalOnProperty(name = "notification.events.enabled", havingValue = "true")
public class KafkaOutboxPublisher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);

    private final OutboxStore outbox;
    private final Producer<String, String> producer;
    private final int batchSize;
    private volatile boolean running;
    private Thread worker;

    public KafkaOutboxPublisher(OutboxStore outbox,
                                @Qualifier("outboxProducer") Producer<String, String> producer,
                                @Value("${notification.events.outbox-batch-size:50}") int batchSize) {
        this.outbox = outbox;
        this.producer = producer;
        this.batchSize = batchSize;
    }

    @Override public void start() {
        if (running) return;
        running = true;
        worker = Thread.ofPlatform().name("notification-outbox").daemon(true).start(this::loop);
    }

    @Override public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }

    private void loop() {
        while (running) {
            try {
                var rows = outbox.claim(batchSize);
                if (rows.isEmpty()) {
                    sleep(1_000);
                    continue;
                }
                for (OutboxStore.Row row : rows) {
                    try {
                        producer.send(new ProducerRecord<>(row.topic(), row.messageKey(), row.payload()))
                            .get(10, TimeUnit.SECONDS);
                        outbox.published(row.id());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running = false;
                        log.warn("Outbox publish interrupted; stopping publisher");
                        break;
                    } catch (Exception e) {
                        log.warn("Outbox event {} publish failed: {}", row.id(), e.getClass().getSimpleName());
                        outbox.failed(row.id(), row.attempts(), "KAFKA_PUBLISH_FAILED");
                    }
                }
            } catch (Exception e) {
                log.warn("Outbox loop failed: {}", e.getClass().getSimpleName());
                sleep(1_000);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
