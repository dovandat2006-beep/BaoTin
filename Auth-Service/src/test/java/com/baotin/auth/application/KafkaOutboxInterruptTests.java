package com.baotin.auth.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaOutboxInterruptTests {
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void interruptedPublisherPreservesFlagAndStopsTheClaimedBatch() throws Exception {
        var queue=mock(DeliveryQueue.class);
        UUID id=UUID.randomUUID(),lease=UUID.randomUUID();
        Map<String,Object> row=Map.of("id",id,"lease_id",lease,"attempts",1,"topic","fixture-events",
            "aggregate_id",UUID.randomUUID(),"envelope","{}");
        when(queue.claim(DeliveryQueue.Kind.EVENT,10)).thenReturn(List.of(row,row));
        Future<RecordMetadata> result=mock(Future.class);
        when(result.get(20,TimeUnit.SECONDS)).thenThrow(new InterruptedException("TEST-FIXTURE-ONLY interrupt"));
        try(MockedConstruction<KafkaProducer> construction=mockConstruction(KafkaProducer.class,
                (producer,context)->when(producer.send(any())).thenReturn(result))) {
            try(var outbox=new KafkaOutbox(queue,"127.0.0.1:1","PLAINTEXT","","")) {
                outbox.publish();
                assertTrue(Thread.currentThread().isInterrupted());
                verify(construction.constructed().getFirst(),times(1)).send(any());
                verify(queue,times(1)).fail(DeliveryQueue.Kind.EVENT,id,lease,1);
            }
        }finally{Thread.interrupted();}
    }
}
