package com.baotin.search;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FullProjectionTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        var db=SearchServiceApplicationTests.DB;
        p.add("spring.datasource.url",db::getJdbcUrl);p.add("spring.datasource.username",db::getUsername);
        p.add("spring.datasource.password",db::getPassword);p.add("spring.flyway.enabled",()->true);
        p.add("search.events.enabled",()->false);
    }
    @Autowired Projection projection;
    @Autowired SearchStore store;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void clear() { jdbc.execute("TRUNCATE search.search_documents,search.inbox_events"); }
    Map<String,Object> snapshot(String kind,UUID ref,String title,String display) {
        return new LinkedHashMap<>(Map.of("visibility","PUBLIC","documentType",kind,"referenceId",ref.toString(),
            "title",title,"displayValue",display,"status","ACTIVE","metadata",Map.of()));
    }
    String event(String kind,String action,UUID ref,String type,String at,Map<String,Object> pub) {
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put(kind.equals("campaign")?"campaignId":"threatTargetId",ref.toString());
        if(!kind.equals("campaign")) payload.put("targetType",type);
        if(pub!=null) payload.put("publicSearch",pub);
        return json.writeValueAsString(Map.of("eventId",UUID.randomUUID().toString(),"eventVersion",1,
            "eventType",kind+"."+action,"producer",kind+"-service","aggregateType",kind.equals("campaign")?"Campaign":"ThreatTarget",
            "aggregateId",ref.toString(),"occurredAt",at,"payload",payload));
    }
    void send(String kind,String body) { projection.ingest("baotin."+kind+".search.events.v1",body); }
    SearchStore.Page find(String q,String type) { return store.find(new SearchQuery(q,type,null,0,20)); }
    @Test void campaignCreatedAndUpdatedAreRealVietnameseFullTextDocuments() {
        UUID ref=UUID.randomUUID();var pub=snapshot("CAMPAIGN",ref,"Chiến dịch mạo danh ngân hàng","Cảnh báo mạo danh");
        pub.put("metadata",Map.of("campaignId",ref.toString(),"referenceType","CAMPAIGN"));
        String body=event("campaign","created",ref,"CAMPAIGN","2026-11-12T00:00:00Z",pub);
        send("campaign",body);send("campaign",body);
        var rows=find("mao danh ngan hang","CAMPAIGN");assertEquals(1,rows.totalItems());
        var doc=rows.items().getFirst();assertEquals(ref,doc.referenceId());assertNotEquals(ref,doc.id());
        assertEquals("ACTIVE",doc.status());assertNull(doc.riskScore());assertEquals(ref.toString(),doc.metadata().get("campaignId"));
        assertEquals(0,find("mao danh","URL").totalItems());
        var update=snapshot("CAMPAIGN",ref,"Chiến dịch giả mạo tuyển dụng","Tuyển dụng giả mạo");
        send("campaign",event("campaign","updated",ref,"CAMPAIGN","2026-11-12T01:00:00Z",update));
        assertEquals("Chiến dịch giả mạo tuyển dụng",find("tuyen dung","CAMPAIGN").items().getFirst().title());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events",Integer.class));
    }
    @Test void privateWithdrawalCannotBeResurrectedByOldEventsOrRisk() {
        UUID ref=UUID.randomUUID();var pub=snapshot("URL",ref,"example.com","example.com");
        send("threat",event("threat","created",ref,"URL","2026-11-12T00:00:00Z",pub));
        send("threat",event("threat","updated",ref,"URL","2026-11-12T02:00:00Z",Map.of("visibility","PRIVATE")));
        send("threat",event("threat","updated",ref,"URL","2026-11-12T01:00:00Z",pub));
        assertEquals(0,find("example","URL").totalItems());
        assertNull(jdbc.queryForObject("SELECT title FROM search.search_documents WHERE reference_id=?",String.class,ref));
        assertEquals("REMOVED",jdbc.queryForObject("SELECT status FROM search.search_documents WHERE reference_id=?",String.class,ref));
        send("threat",event("threat","updated",ref,"URL","2026-11-12T03:00:00Z",pub));
        assertEquals(1,find("example","URL").totalItems());
    }
    @Test void allFiveNamedDomainEventsAreAcceptedAndLegacyEventsDoNotPublishPrivateContent() {
        UUID target=UUID.randomUUID(),campaign=UUID.randomUUID();
        send("threat",event("threat","created",target,"URL","2026-11-12T00:00:00Z",null));
        send("threat",event("threat","updated",target,"URL","2026-11-12T00:01:00Z",null));
        send("campaign",event("campaign","created",campaign,"CAMPAIGN","2026-11-12T00:02:00Z",null));
        send("campaign",event("campaign","updated",campaign,"CAMPAIGN","2026-11-12T00:03:00Z",null));
        var risk=new LinkedHashMap<String,Object>();risk.put("threatTargetId",target.toString());risk.put("targetType","URL");
        risk.put("changedAt","2026-11-12T00:04:00Z");risk.put("newScore",84);risk.put("newLevel","CRITICAL");
        projection.ingest("baotin.reputation.events.v1",json.writeValueAsString(Map.of("eventId",UUID.randomUUID().toString(),
            "eventVersion",1,"eventType","reputation.score.changed","producer","reputation-service","aggregateType","ThreatTarget",
            "aggregateId",target.toString(),"payload",risk)));
        assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events",Integer.class));
        assertEquals(0,find("example",null).totalItems());
        send("threat",event("threat","updated",target,"URL","2026-11-12T00:05:00Z",snapshot("URL",target,"example.com","example.com")));
        assertEquals(84d,find("example","URL").items().getFirst().riskScore());
    }
    @Test void exactContactAndAccountLookupsReturnOnlyMaskedPublicValues() {
        for(var row:List.of(new String[]{"PHONE","Điện thoại","***1234","0901231234"},
                new String[]{"BANK_ACCOUNT","Tài khoản ngân hàng","***5678","12345678"},
                new String[]{"EMAIL","Email","a***@example.com","alice@example.com"})) {
            UUID ref=UUID.randomUUID();var pub=snapshot(row[0],ref,row[1],row[2]);
            pub.put("lookupHash",SearchTypes.lookupHash(row[0],row[3]));
            send("threat",event("threat","created",ref,row[0],"2026-11-12T00:00:00Z",pub));
            var found=find(row[3],row[0]);assertEquals(1,found.totalItems());assertEquals(row[2],found.items().getFirst().displayValue());
            String stored=jdbc.queryForObject("SELECT coalesce(title,'')||coalesce(display_value,'')||coalesce(search_text,'')||metadata::text FROM search.search_documents WHERE reference_id=?",String.class,ref);
            assertFalse(stored.contains(row[3]));assertFalse(json.writeValueAsString(found).contains("lookupHash"));
        }
        assertEquals(1,find("+84 901 231 234","PHONE").totalItems());
        assertEquals(0,find("alice@example.com","PHONE").totalItems());
    }
    @Test void rawContactValuesAndArbitraryMetadataAreRejectedAtomically() {
        UUID ref=UUID.randomUUID();var phone=snapshot("PHONE",ref,"Điện thoại","0901231234");
        phone.put("lookupHash",SearchTypes.lookupHash("PHONE","0901231234"));
        assertThrows(IllegalArgumentException.class,()->send("threat",event("threat","created",ref,"PHONE","2026-11-12T00:00:00Z",phone)));
        var campaign=snapshot("CAMPAIGN",ref,"Public campaign","Public campaign");campaign.put("metadata",Map.of("email","private@example.com"));
        assertThrows(IllegalArgumentException.class,()->send("campaign",event("campaign","created",ref,"CAMPAIGN","2026-11-12T00:00:00Z",campaign)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search.search_documents",Integer.class));
    }
    @Test void validLongDnsHostsKeepLegacyAndNewPublicationCompatibility() {
        String host=("a".repeat(60)+".").repeat(3)+"b".repeat(60)+".com";
        UUID ref=UUID.randomUUID();assertTrue(host.length()>200);assertTrue(Projection.publicHost(host));
        send("threat",event("threat","created",ref,"URL","2026-11-12T00:00:00Z",snapshot("URL",ref,host,host)));
        assertEquals(host,find(host,"URL").items().getFirst().displayValue());
    }
    @Test void additiveMigrationStillAcceptsTheOriginalUrlWriter() {
        UUID ref=UUID.randomUUID();
        jdbc.update("INSERT INTO search.search_documents(id,title,display_value,search_text,target_at,target_event,updated_at) VALUES (?,?,?,?,now(),?,now())",ref,"legacy.example.com","legacy.example.com","legacy.example.com",UUID.randomUUID());
        var row=find("legacy.example.com","URL").items().getFirst();
        assertEquals(ref,row.referenceId());assertEquals("ACTIVE",row.status());assertTrue(row.metadata().isEmpty());
    }
    @Test void publicationPolicyRejectsUnmaskedOrUnapprovedPublicFields() {
        UUID ref=UUID.randomUUID();
        for(var item:List.of(new String[]{"EMAIL","Email","alice@example.com"},new String[]{"CAMPAIGN","Gọi 0901231234","Campaign"},new String[]{"OTHER","Raw private message","Raw private message"})) {
            var pub=snapshot(item[0],ref,item[1],item[2]);
            if(item[0].equals("EMAIL")) pub.put("lookupHash",SearchTypes.lookupHash("EMAIL","alice@example.com"));
            String kind=item[0].equals("CAMPAIGN")?"campaign":"threat";
            assertThrows(IllegalArgumentException.class,()->send(kind,event(kind,"created",ref,item[0],"2026-11-12T00:00:00Z",pub)));
        }
        var bank=snapshot("BANK_ACCOUNT",ref,"Tài khoản ngân hàng","***5678");
        bank.put("lookupHash",SearchTypes.lookupHash("BANK_ACCOUNT","12345678"));bank.put("metadata",Map.of("bankCode","12345678"));
        assertThrows(IllegalArgumentException.class,()->send("threat",event("threat","created",ref,"BANK_ACCOUNT","2026-11-12T00:00:00Z",bank)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events",Integer.class));
    }
    @Test void maskedContentPrefixesMayEndOnWhitespaceWithoutRejectingTheMessage() {
        UUID ref=UUID.randomUUID();String text="a ".repeat(99)+"b "+"c ".repeat(26)+" "+"Cảnh báo chuyển khoản OTP ***";
        var pub=snapshot("OTHER",ref,text.substring(0,200),text.substring(0,253));pub.put("content",text);
        pub.put("metadata",Map.of("referenceType","MESSAGE_TEMPLATE","contentWarning","SUSPICIOUS"));
        send("threat",event("threat","updated",ref,"MESSAGE_TEMPLATE","2026-11-12T00:00:00Z",pub));
        var found=find("canh bao","OTHER");assertEquals(1,found.totalItems());assertEquals(text,found.items().getFirst().content());
        assertEquals(text.substring(0,200).strip(),found.items().getFirst().title());
        assertEquals(text.substring(0,253).strip(),found.items().getFirst().displayValue());
    }
    @Test void benignDatasetLabelDoesNotInventAScoreOrVerification() {
        UUID ref=UUID.randomUUID();var pub=snapshot("URL",ref,"normal.example.com","normal.example.com");
        pub.put("metadata",Map.of("referenceType","URL","contentWarning","BENIGN"));
        send("threat",event("threat","updated",ref,"URL","2026-11-12T00:00:00Z",pub));
        var doc=find("normal","URL").items().getFirst();assertEquals("BENIGN",doc.metadata().get("contentWarning"));
        assertNull(doc.riskScore());assertNull(doc.riskLevel());assertEquals("ACTIVE",doc.status());
    }
    @Test void maskedMessageWordingIsSearchableWithoutSourceOrPrivateFields() {
        UUID ref=UUID.randomUUID();String text="Cảnh báo khóa tài khoản! Nhập OTP *** tại https://example.com/***";
        var pub=snapshot("OTHER",ref,text,text);pub.put("content",text);pub.put("metadata",Map.of("referenceType","MESSAGE_TEMPLATE","contentWarning","SUSPICIOUS"));
        send("threat",event("threat","updated",ref,"MESSAGE_TEMPLATE","2026-11-12T00:00:00Z",pub));
        var found=find("khoa tai khoan","OTHER");assertEquals(1,found.totalItems());assertEquals(text,found.items().getFirst().content());
        assertFalse(found.items().getFirst().metadata().containsKey("source"));
        send("threat",event("threat","updated",ref,"MESSAGE_TEMPLATE","2026-11-12T01:00:00Z",Map.of("visibility","PRIVATE")));
        assertEquals(0,find("khoa tai khoan","OTHER").totalItems());
        assertNull(jdbc.queryForObject("SELECT public_content FROM search.search_documents WHERE reference_id=?",String.class,ref));
        var privatePub=snapshot("OTHER",UUID.randomUUID(),"Email alice@example.com","Email alice@example.com");privatePub.put("content","Email alice@example.com");
        assertThrows(IllegalArgumentException.class,()->Publication.parse(json.valueToTree(privatePub),"OTHER",UUID.fromString(privatePub.get("referenceId").toString())));
    }
    @Test void sourceIdentityAndPublicationReferenceMustMatch() {
        UUID ref=UUID.randomUUID();var wrong=snapshot("CAMPAIGN",UUID.randomUUID(),"Public campaign","Public campaign");
        assertThrows(IllegalArgumentException.class,()->send("campaign",event("campaign","created",ref,"CAMPAIGN","2026-11-12T00:00:00Z",wrong)));
        String body=event("campaign","created",ref,"CAMPAIGN","2026-11-12T00:00:00Z",null);
        assertThrows(IllegalArgumentException.class,()->send("threat",body));
        assertThrows(IllegalArgumentException.class,()->send("campaign",body.replace("campaign-service","threat-service")));
        assertThrows(IllegalArgumentException.class,()->send("campaign",body.replace("\"eventVersion\":1","\"eventVersion\":\"1\"")));
    }
}
