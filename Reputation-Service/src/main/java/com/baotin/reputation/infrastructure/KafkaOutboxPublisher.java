package com.baotin.reputation.infrastructure;

import com.baotin.reputation.application.OutboxStore;
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

@Component
@ConditionalOnProperty(name = "reputation.events.enabled", havingValue = "true")
public class KafkaOutboxPublisher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);

    private final OutboxStore outbox;
    private final Producer<String, String> producer;
    private final int batchSize;
    private volatile boolean running;
    private Thread worker;

    public KafkaOutboxPublisher(OutboxStore outbox,
                                @Qualifier("outboxProducer") Producer<String, String> producer,
                                @Value("${reputation.events.outbox-batch-size:50}") int batchSize) {
        this.outbox = outbox;
        this.producer = producer;
        this.batchSize = batchSize;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofPlatform().name("reputation-outbox").daemon(true).start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

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
                        if (!outbox.published(row.id(), row.leaseId())) {
                            log.warn("Outbox event {} lease expired before publish acknowledgement", row.id());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running = false;
                        log.warn("Outbox publish interrupted; stopping publisher");
                        break;
                    } catch (Exception e) {
                        log.warn("Outbox event {} publish failed: {}", row.id(), e.getClass().getSimpleName());
                        if (!outbox.failed(row.id(), row.leaseId(), row.attempts(), "KAFKA_PUBLISH_FAILED")) {
                            log.warn("Outbox event {} lease expired before failure acknowledgement", row.id());
                        }
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
