package com.baotin.auth.application;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@ConditionalOnProperty(name="auth.events.enabled",havingValue="true")
public class KafkaOutbox implements AutoCloseable {
    private final DeliveryQueue queue;private final KafkaProducer<String,String> producer;
    public KafkaOutbox(DeliveryQueue queue,@Value("${auth.events.bootstrap-servers:}") String bootstrap,@Value("${auth.events.security-protocol:PLAINTEXT}") String protocol,@Value("${auth.events.sasl-mechanism:}") String mechanism,@Value("${auth.events.sasl-jaas-config:}") String jaas){
        if(bootstrap.isBlank())throw new IllegalArgumentException("Kafka bootstrap required when publishing enabled");this.queue=queue;
        var props=new Properties();props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG,"all");props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,15000);props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,5000);props.put("security.protocol",protocol);
        if(!mechanism.isBlank()){props.put("sasl.mechanism",mechanism);props.put("sasl.jaas.config",jaas);}producer=new KafkaProducer<>(props);
    }
    @Scheduled(fixedDelayString="${auth.events.interval-ms:1000}",initialDelay=5000)
    public void publish(){
        for(var row:queue.claim(DeliveryQueue.Kind.EVENT,10)){
            UUID id=(UUID)row.get("id"),lease=(UUID)row.get("lease_id");int attempts=(Integer)row.get("attempts");
            try{
                producer.send(new ProducerRecord<>((String)row.get("topic"),row.get("aggregate_id").toString(),row.get("envelope").toString())).get(20,TimeUnit.SECONDS);
                queue.complete(DeliveryQueue.Kind.EVENT,id,lease);
            }catch(InterruptedException e){
                try{queue.fail(DeliveryQueue.Kind.EVENT,id,lease,attempts);}
                finally{Thread.currentThread().interrupt();}
                return;
            }catch(Exception e){queue.fail(DeliveryQueue.Kind.EVENT,id,lease,attempts);}
        }
    }
    @Override public void close(){producer.close(java.time.Duration.ofSeconds(5));}
}
