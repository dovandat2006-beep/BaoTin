package com.baotin.search;

import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SearchKafkaTests {
    static final KafkaContainer BROKER = new KafkaContainer("apache/kafka-native:3.8.0");
    static {
        BROKER.start();
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",BROKER.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("baotin.search.targets.v1",1,(short)1),
                new NewTopic("baotin.reputation.events.v1",1,(short)1),
                new NewTopic("baotin.threat.search.events.v1",1,(short)1),
                new NewTopic("baotin.campaign.search.events.v1",1,(short)1))).all().get();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        var db=SearchServiceApplicationTests.DB;
        p.add("spring.datasource.url",db::getJdbcUrl);p.add("spring.datasource.username",db::getUsername);
        p.add("spring.datasource.password",db::getPassword);p.add("spring.flyway.enabled",()->true);
        p.add("search.events.enabled",()->true);p.add("spring.kafka.bootstrap-servers",BROKER::getBootstrapServers);
        p.add("spring.kafka.consumer.group-id",()->"search-kafka-test");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchStore store;
    @org.junit.jupiter.api.BeforeEach void clearProjection() { jdbc.execute("TRUNCATE search.search_documents,search.inbox_events"); }
    @Test void campaignPublicationTravelsThroughTheRealBroker() throws Exception {
        UUID id=UUID.randomUUID(),event=UUID.randomUUID();
        String body="""
            {"eventId":"%s","eventType":"campaign.created","eventVersion":1,"producer":"campaign-service",
             "aggregateType":"Campaign","aggregateId":"%s","occurredAt":"2026-11-12T00:00:00Z",
             "payload":{"campaignId":"%s","publicSearch":{"visibility":"PUBLIC","documentType":"CAMPAIGN",
             "referenceId":"%s","title":"Chiến dịch mạo danh tuyển dụng","displayValue":"Tuyển dụng giả mạo",
             "status":"ACTIVE","metadata":{}}}}
            """.formatted(event,id,id,id);
        try(var producer=new KafkaProducer<String,String>(Map.of("bootstrap.servers",BROKER.getBootstrapServers(),
                "key.serializer",StringSerializer.class.getName(),"value.serializer",StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>("baotin.campaign.search.events.v1",id.toString(),body)).get();
        }
        long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<until && jdbc.queryForObject("SELECT count(*) FROM search.inbox_events WHERE event_id=?",Integer.class,event)==0) Thread.sleep(100);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events WHERE event_id=?",Integer.class,event));
        var result=store.find(new SearchQuery("mao danh tuyen dung","CAMPAIGN",null,0,20));
        assertEquals(1,result.totalItems());assertEquals(id,result.items().getFirst().referenceId());
    }
    @Test void brokerDeliveryProjectsExactlyOnceBeforeCommitting() throws Exception {
        UUID id=UUID.randomUUID(),event=UUID.randomUUID();
        String body="""
            {"eventId":"%s","eventType":"search.target.updated","eventVersion":1,"producer":"threat-service",
             "aggregateType":"ThreatTarget","aggregateId":"%s","occurredAt":"2026-11-11T00:00:00Z",
             "payload":{"targetId":"%s","targetType":"URL","displayValue":"broker-example.com"}}
            """.formatted(event,id,id);
        try(var producer=new KafkaProducer<String,String>(Map.of("bootstrap.servers",BROKER.getBootstrapServers(),
                "key.serializer",StringSerializer.class.getName(),"value.serializer",StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>("baotin.search.targets.v1",id.toString(),body)).get();
            producer.send(new ProducerRecord<>("baotin.search.targets.v1",id.toString(),body)).get();
        }
        long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        boolean committed=false;
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",BROKER.getBootstrapServers()))) {
            while(System.nanoTime()<until) {
                var offsets=admin.listConsumerGroupOffsets("search-kafka-test").partitionsToOffsetAndMetadata().get();
                committed=offsets.values().stream().anyMatch(v->v.offset()>=2);
                if(committed) break;
                Thread.sleep(100);
            }
        }
        assertTrue(committed,"Both offsets must commit after projection succeeds");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events WHERE event_id=?",Integer.class,event));
        assertEquals("broker-example.com",store.find(new SearchQuery("broker-example",null,null,0,20)).items().getFirst().displayValue());
    }
}
