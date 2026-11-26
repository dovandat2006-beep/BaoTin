package com.baotin.report.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;

/** At-least-once: a broker acknowledgement lost before DB commit can publish twice. */
@Component
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String,String> kafka;
    private final TransactionTemplate transactions;
    private final String topic;
    public OutboxPublisher(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka,TransactionTemplate transactions,
        @Value("${report.events-topic}") String topic,MeterRegistry metrics) {
        this.jdbc=jdbc;this.kafka=kafka;this.transactions=transactions;this.topic=topic;
        metrics.gauge("baotin_outbox_pending_total",this,p->p.pending());
    }
    private double pending() { return jdbc.queryForObject("SELECT count(*) FROM report.outbox_events WHERE published_at IS NULL",Long.class); }
    @Scheduled(fixedDelayString="${report.outbox-delay-ms:1000}")
    public void publish() {
        transactions.executeWithoutResult(tx->{
            var rows=jdbc.queryForList("SELECT e.* FROM report.outbox_events e WHERE e.published_at IS NULL AND e.next_attempt_at<=? AND NOT EXISTS (SELECT 1 FROM report.outbox_events prior WHERE prior.aggregate_id=e.aggregate_id AND prior.published_at IS NULL AND prior.publication_order<e.publication_order) ORDER BY e.publication_order LIMIT 20 FOR UPDATE SKIP LOCKED",Timestamp.from(Instant.now()));
            for(var row:rows) {
                try {
                    kafka.send(topic,row.get("aggregate_id").toString(),row.get("payload").toString()).get(5,TimeUnit.SECONDS);
                    jdbc.update("UPDATE report.outbox_events SET published_at=?,last_error=NULL WHERE id=?",Timestamp.from(Instant.now()),row.get("id"));
                } catch(Exception e) {
                    if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                    int attempts=((Number)row.get("attempts")).intValue()+1;
                    jdbc.update("UPDATE report.outbox_events SET attempts=?,next_attempt_at=?,last_error='BROKER_UNAVAILABLE' WHERE id=?",attempts,Timestamp.from(Instant.now().plusSeconds(Math.min(300,1L<<Math.min(attempts,8)))),row.get("id"));
                    break;
                }
            }
        });
    }
}
