package com.baotin.search;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

record ProjectionChange(UUID event,UUID reference,String type,Instant at,Publication publication,Double score,String level) {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static ProjectionChange parse(String topic,String body) {
        try { return validated(topic,body); }
        catch(RuntimeException ignored) { throw new IllegalArgumentException("Invalid Search projection event"); }
    }
    private static ProjectionChange validated(String topic,String body) {
        if(body==null || body.length()>65536) throw invalid();
        JsonNode e=JSON.readTree(body),p=e.path("payload");
        if(!e.path("eventVersion").isIntegralNumber() || e.path("eventVersion").asInt()!=1) throw invalid();
        String eventType=Projection.text(e,"eventType");
        boolean risk=topic.equals("baotin.reputation.events.v1");
        boolean legacy=topic.equals("baotin.search.targets.v1");
        boolean campaign=topic.equals("baotin.campaign.search.events.v1");
        String producer;
        if(risk) { producer="reputation-service";if(!eventType.equals("reputation.score.changed")) throw invalid(); }
        else if(legacy) { producer="threat-service";if(!eventType.equals("search.target.updated")) throw invalid(); }
        else if(campaign) { producer="campaign-service";if(!Set.of("campaign.created","campaign.updated").contains(eventType)) throw invalid(); }
        else if(topic.equals("baotin.threat.search.events.v1")) { producer="threat-service";if(!Set.of("threat.created","threat.updated").contains(eventType)) throw invalid(); }
        else throw invalid();
        if(!Projection.text(e,"producer").equals(producer) || !Projection.text(e,"aggregateType").equals(campaign?"Campaign":"ThreatTarget")) throw invalid();
        UUID event=UUID.fromString(Projection.text(e,"eventId"));
        UUID reference=UUID.fromString(Projection.text(p,campaign?"campaignId":legacy?"targetId":"threatTargetId"));
        if(!UUID.fromString(Projection.text(e,"aggregateId")).equals(reference)) throw invalid();
        String type=campaign?"CAMPAIGN":SearchTypes.fromTarget(Projection.text(p,"targetType"));
        Instant at=Instant.parse(Projection.text(risk?p:e,risk?"changedAt":"occurredAt"));
        if(risk) return risk(event,reference,type,at,p);
        Publication publication=legacy?Publication.host(Projection.text(p,"displayValue")):
            p.path("publicSearch").isMissingNode() || p.path("publicSearch").isNull() ? null : Publication.parse(p.path("publicSearch"),type,reference);
        if(legacy && !type.equals("URL")) throw invalid();
        return new ProjectionChange(event,reference,type,at,publication,null,null);
    }
    private static ProjectionChange risk(UUID event,UUID reference,String type,Instant at,JsonNode p) {
        double score=p.path("newScore").asDouble(-1);String level=Projection.text(p,"newLevel");
        if(!p.path("newScore").isNumber() || !Double.isFinite(score) || score<0 || score>100
            || !Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(level)) throw invalid();
        return new ProjectionChange(event,reference,type,at,null,score,level);
    }
    static ProjectionChange ownerSnapshot(JsonNode row) {
        try {
            if(!Projection.text(row,"kind").equals("OWNER_SNAPSHOT_V1"))throw invalid();
            UUID id=UUID.fromString(Projection.text(row,"recordId")),ref=UUID.fromString(Projection.text(row,"referenceId"));
            String source=Projection.text(row,"source"),type=Projection.text(row,"documentType");
            if(!SearchTypes.ALL.contains(type))throw invalid();
            Instant at=Instant.parse(Projection.text(row,"observedAt"));
            if(source.equals("reputation-service")) {
                if(type.equals("CAMPAIGN"))throw invalid();
                return risk(id,ref,type,at,row.path("risk"));
            }
            if(!(source.equals("threat-service") && !type.equals("CAMPAIGN") || source.equals("campaign-service") && type.equals("CAMPAIGN")))throw invalid();
            return new ProjectionChange(id,ref,type,at,Publication.parse(row.path("publicSearch"),type,ref),null,null);
        } catch(RuntimeException ignored) { throw new IllegalArgumentException("Invalid owner Search snapshot"); }
    }
    UUID documentId() {
        // Namespaced display identity only, not a password/signature/security digest.
        return type.equals("CAMPAIGN") ? UUID.nameUUIDFromBytes(("campaign:"+reference).getBytes(StandardCharsets.UTF_8)) : reference;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid projection envelope"); }
}
