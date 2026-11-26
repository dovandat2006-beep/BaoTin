package com.baotin.reputation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.baotin.reputation.application.OutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;

class KafkaOutboxPublisherTest {

    @Test
    void interruptDuringPublishStopsPublisherWithoutMarkingFailure() throws Exception {
        AtomicInteger failedCalls = new AtomicInteger();
        CountDownLatch firstSend = new CountDownLatch(1);
        KafkaOutboxPublisher publisher =
            new KafkaOutboxPublisher(new StubOutbox(failedCalls), new InterruptingProducer(firstSend), 10);

        publisher.start();
        assertThat(firstSend.await(5, TimeUnit.SECONDS)).isTrue();
        publisher.stop();

        assertThat(publisher.isRunning()).isFalse();
        assertThat(failedCalls).hasValue(0);
    }

    private static final class StubOutbox extends OutboxStore {
        private final AtomicInteger failedCalls;

        private StubOutbox(AtomicInteger failedCalls) {
            super(null, Clock.systemUTC());
            this.failedCalls = failedCalls;
        }

        @Override
        public List<Row> claim(int batch) {
            return List.of(new Row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "reputation.events", "key", "{\"eventId\":\"test\"}", 1));
        }

        @Override
        public boolean published(UUID id, UUID leaseId) {
            return true;
        }

        @Override
        public boolean failed(UUID id, UUID leaseId, int attempts, String error) {
            failedCalls.incrementAndGet();
            return true;
        }
    }

    private static final class InterruptingProducer implements Producer<String, String> {
        private final CountDownLatch firstSend;

        private InterruptingProducer(CountDownLatch firstSend) {
            this.firstSend = firstSend;
        }

        @Override
        public Future<RecordMetadata> send(ProducerRecord<String, String> record) {
            firstSend.countDown();
            return new InterruptedFuture();
        }

        @Override
        public Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
            return send(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public List<PartitionInfo> partitionsFor(String topic) {
            return List.of();
        }

        @Override
        public Map<MetricName, ? extends Metric> metrics() {
            return Map.of();
        }

        @Override
        public void close() {
        }

        @Override
        public void close(Duration timeout) {
        }

        @Override
        public org.apache.kafka.common.Uuid clientInstanceId(Duration timeout) {
            return org.apache.kafka.common.Uuid.ZERO_UUID;
        }

        @Override
        public void initTransactions() {
            throw new UnsupportedOperationException("transactions are not used by the outbox publisher");
        }

        @Override
        public void beginTransaction() {
            throw new UnsupportedOperationException("transactions are not used by the outbox publisher");
        }

        @Override
        public void sendOffsetsToTransaction(
            Map<org.apache.kafka.common.TopicPartition,
                org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets,
            org.apache.kafka.clients.consumer.ConsumerGroupMetadata groupMetadata) {
            throw new UnsupportedOperationException("transactions are not used by the outbox publisher");
        }

        @Override
        public void commitTransaction() {
            throw new UnsupportedOperationException("transactions are not used by the outbox publisher");
        }

        @Override
        public void abortTransaction() {
            throw new UnsupportedOperationException("transactions are not used by the outbox publisher");
        }

        @Override
        public void registerMetricForSubscription(org.apache.kafka.common.metrics.KafkaMetric metric) {
            throw new UnsupportedOperationException("metric subscriptions are not used by the outbox publisher");
        }

        @Override
        public void unregisterMetricFromSubscription(org.apache.kafka.common.metrics.KafkaMetric metric) {
            throw new UnsupportedOperationException("metric subscriptions are not used by the outbox publisher");
        }
    }

    private static final class InterruptedFuture implements Future<RecordMetadata> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public RecordMetadata get() {
            throw new IllegalStateException("blocking get is not used by the publisher");
        }

        @Override
        public RecordMetadata get(long timeout, TimeUnit unit) throws InterruptedException {
            throw new InterruptedException("simulated publisher shutdown");
        }
    }
}
