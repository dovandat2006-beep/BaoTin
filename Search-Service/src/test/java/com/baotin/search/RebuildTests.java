package com.baotin.search;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RebuildTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        var db=SearchServiceApplicationTests.DB;
        p.add("spring.datasource.url",db::getJdbcUrl);p.add("spring.datasource.username",db::getUsername);
        p.add("spring.datasource.password",db::getPassword);p.add("spring.flyway.enabled",()->true);
        p.add("search.events.enabled",()->false);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired Projection projection;
    @Autowired SearchStore store;
    @TempDir Path dir;
    JdbcTemplate operator;
    RebuildManager manager;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE search.rebuild_inbox,search.rebuild_documents,search.rebuild_runs,search.inbox_events,search.search_documents,search.projection_journal RESTART IDENTITY");
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='baotin_search_migrator') THEN CREATE ROLE baotin_search_migrator LOGIN PASSWORD 'FIXTURE-ONLY-migrator-password'; END IF; IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='search_runtime_fixture') THEN CREATE ROLE search_runtime_fixture LOGIN PASSWORD 'FIXTURE-ONLY-runtime-password'; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA search TO baotin_search_migrator,search_runtime_fixture; GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA search TO baotin_search_migrator; GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA search TO baotin_search_migrator; GRANT SELECT,INSERT,UPDATE ON search.search_documents TO search_runtime_fixture; GRANT SELECT,INSERT ON search.inbox_events,search.projection_journal TO search_runtime_fixture; GRANT USAGE ON SEQUENCE search.projection_journal_sequence_seq TO search_runtime_fixture");
        var db=SearchServiceApplicationTests.DB;
        operator=new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),"baotin_search_migrator","FIXTURE-ONLY-migrator-password"));
        manager=new RebuildManager(operator);
    }
    String publication(UUID ref,String host,String at,boolean visible) {
        Map<String,Object> snapshot=visible?Map.of("visibility","PUBLIC","documentType","URL","referenceId",ref.toString(),"title",host,"displayValue",host,"status","ACTIVE","metadata",Map.of()):Map.of("visibility","PRIVATE");
        return json.writeValueAsString(Map.of("eventId",UUID.randomUUID().toString(),"eventType","threat.updated","eventVersion",1,
            "producer","threat-service","aggregateType","ThreatTarget","aggregateId",ref.toString(),"occurredAt",at,
            "payload",Map.of("threatTargetId",ref.toString(),"targetType","URL","publicSearch",snapshot)));
    }
    String risk(UUID ref,int value,String at) {
        return json.writeValueAsString(Map.of("eventId",UUID.randomUUID().toString(),"eventType","reputation.score.changed","eventVersion",1,
            "producer","reputation-service","aggregateType","ThreatTarget","aggregateId",ref.toString(),
            "payload",Map.of("threatTargetId",ref.toString(),"targetType","URL","newScore",value,"newLevel",value>=75?"CRITICAL":"LOW","changedAt",at)));
    }
    String line(String body) {
        if(json.readTree(body).path("kind").isString())return body;
        String producer=json.readTree(body).path("producer").asString();
        return json.writeValueAsString(Map.of("topic",producer.equals("reputation-service")?"baotin.reputation.events.v1":"baotin.threat.search.events.v1","event",json.readTree(body)));
    }
    Path manifest(RebuildManager.Run run,List<String> bodies) throws Exception {
        Path data=dir.resolve(run.id()+".jsonl");StringBuilder text=new StringBuilder();
        for(String body:bodies)text.append(line(body)).append('\n');Files.writeString(data,text,StandardCharsets.UTF_8);
        var sources=new ArrayList<Map<String,Object>>();
        for(String name:List.of("threat-service","reputation-service","campaign-service"))sources.add(Map.of("source",name,"snapshotAt",run.startedAt().plusSeconds(1).toString(),"complete",true));
        Path file=dir.resolve(run.id()+".manifest.json");
        Files.writeString(file,json.writeValueAsString(Map.of("schemaVersion",1,"runId",run.id().toString(),"dataFile",data.toString(),"sha256",RebuildManifest.digest(data),"records",bodies.size(),"sources",sources)));
        return file;
    }
    SearchStore.Page find(String query) { return store.find(new SearchQuery(query,"URL",null,0,20)); }
    @Test void resumableRebuildKeepsLiveViewAndMergesWritesAfterTheCaptureBarrier() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        String first=publication(a,"before.example.com","2026-11-12T00:00:00Z",true);
        projection.ingest("baotin.threat.search.events.v1",first);
        var run=manager.begin();Path manifest=manifest(run,List.of(first,first,risk(a,10,"2026-11-12T00:01:00Z"),publication(b,"seed.example.com","2026-11-12T00:00:00Z",true)));
        var paused=manager.resume(run.id(),manifest,2);assertEquals(2,paused.checkpoint());assertEquals("BUILDING",paused.status());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search.search_documents WHERE reference_id=?",Integer.class,b));
        projection.ingest("baotin.threat.search.events.v1",publication(a,"changed.example.com","2026-11-12T03:00:00Z",true));
        projection.ingest("baotin.reputation.events.v1",risk(a,90,"2026-11-12T04:00:00Z"));
        projection.ingest("baotin.threat.search.events.v1",publication(c,"during.example.com","2026-11-12T03:00:00Z",true));
        assertEquals("READY",manager.resume(run.id(),manifest,2).status());
        assertEquals("COMPLETED",manager.cutover(run.id()).status());
        assertEquals(90d,find("changed.example.com").items().getFirst().riskScore());
        assertTrue(find("seed.example.com").items().stream().anyMatch(row->row.referenceId().equals(b)));assertTrue(find("during.example.com").items().stream().anyMatch(row->row.referenceId().equals(c)));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search.search_documents WHERE reference_id=?",Integer.class,b));
        assertThrows(IllegalStateException.class,()->manager.cutover(run.id()));
        assertThrows(IllegalStateException.class,()->manager.resume(run.id(),manifest,2));
    }
    @Test void deltaMergeDoesNotReplaceNewerPublicationWithAnOlderRiskRowSnapshot() throws Exception {
        UUID ref=UUID.randomUUID();projection.ingest("baotin.threat.search.events.v1",publication(ref,"old.example.com","2026-11-12T00:00:00Z",true));
        var run=manager.begin();Path manifest=manifest(run,List.of(publication(ref,"new.example.com","2026-11-12T03:00:00Z",true),risk(ref,10,"2026-11-12T01:00:00Z")));
        projection.ingest("baotin.reputation.events.v1",risk(ref,85,"2026-11-12T04:00:00Z"));
        manager.resume(run.id(),manifest,100);manager.cutover(run.id());
        assertEquals("new.example.com",find("new.example.com").items().getFirst().title());
        assertEquals(85d,find("new.example.com").items().getFirst().riskScore());
    }
    @Test void withdrawalDuringRebuildSurvivesCutoverAndLaterRisk() throws Exception {
        UUID ref=UUID.randomUUID();String visible=publication(ref,"private.example.com","2026-11-12T00:00:00Z",true);
        projection.ingest("baotin.threat.search.events.v1",visible);var run=manager.begin();Path manifest=manifest(run,List.of(visible));
        projection.ingest("baotin.threat.search.events.v1",publication(ref,"ignored.example.com","2026-11-12T03:00:00Z",false));
        projection.ingest("baotin.reputation.events.v1",risk(ref,85,"2026-11-12T04:00:00Z"));
        manager.resume(run.id(),manifest,100);manager.cutover(run.id());
        assertEquals(0,find("private.example.com").totalItems());
        assertEquals("REMOVED",jdbc.queryForObject("SELECT status FROM search.search_documents WHERE reference_id=?",String.class,ref));
    }
    @Test void changedInputAndStaleOrIncompleteSnapshotsNeverReplaceTheLiveIndex() throws Exception {
        UUID ref=UUID.randomUUID();String body=publication(ref,"stable.example.com","2026-11-12T00:00:00Z",true);
        projection.ingest("baotin.threat.search.events.v1",body);var run=manager.begin();Path manifest=manifest(run,List.of(body,body));
        manager.resume(run.id(),manifest,1);
        Files.writeString(dir.resolve(run.id()+".jsonl"),"changed",StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,()->manager.resume(run.id(),manifest,1));
        assertEquals(1,manager.status(run.id()).checkpoint());assertEquals(1,find("stable.example.com").totalItems());
        manager.cancel(run.id());manager.purge(run.id());
        var next=manager.begin();Path bad=manifest(next,List.of());
        Files.writeString(bad,Files.readString(bad).replace(next.startedAt().plusSeconds(1).toString(),next.startedAt().minusSeconds(1).toString()));
        assertThrows(IllegalArgumentException.class,()->manager.resume(next.id(),bad,1));
        assertThrows(IllegalStateException.class,()->manager.cutover(next.id()));
    }
    @Test void actualFailureAfterReplacementRollsBackLiveDataInboxAndRunState() throws Exception {
        UUID old=UUID.randomUUID();projection.ingest("baotin.threat.search.events.v1",publication(old,"original.example.com","2026-11-12T00:00:00Z",true));
        var run=manager.begin();Path manifest=manifest(run,List.of(publication(UUID.randomUUID(),"replacement.example.com","2026-11-12T00:00:00Z",true)));
        manager.resume(run.id(),manifest,100);int inbox=jdbc.queryForObject("SELECT count(*) FROM search.inbox_events",Integer.class);
        var outer=new TransactionTemplate(new DataSourceTransactionManager(operator.getDataSource()));
        assertThrows(IllegalStateException.class,()->outer.execute(status->{manager.cutover(run.id());throw new IllegalStateException("synthetic rollback after replacement");}));
        assertEquals("READY",manager.status(run.id()).status());assertEquals(1,find("original.example.com").totalItems());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search.search_documents WHERE title='replacement.example.com'",Integer.class));assertEquals(inbox,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events",Integer.class));
    }
    @Test void runtimeRoleCannotRebuildAndTheOriginalWriterIsJournaled() throws Exception {
        var db=SearchServiceApplicationTests.DB;
        var runtime=new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),"search_runtime_fixture","FIXTURE-ONLY-runtime-password"));
        assertThrows(IllegalStateException.class,()->new RebuildManager(runtime).begin());
        var run=manager.begin();Path manifest=manifest(run,List.of());UUID ref=UUID.randomUUID();
        runtime.update("INSERT INTO search.search_documents(id,title,display_value,search_text,target_at,target_event,updated_at) VALUES (?,?,?,?,now(),?,now())",ref,"legacy.example.com","legacy.example.com","legacy.example.com",UUID.randomUUID());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search.projection_journal WHERE document_id=?",Integer.class,ref));
        manager.resume(run.id(),manifest,100);manager.cutover(run.id());assertEquals(1,find("legacy.example.com").totalItems());
        assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.queryForList("SELECT * FROM search.rebuild_runs"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.execute("CREATE TABLE search.forbidden_rebuild(id int)"));
    }
    @Test void preCutoverRepeatableReadSnapshotNeverSeesAnEmptyOrPartialReplacement() throws Exception {
        UUID old=UUID.randomUUID();projection.ingest("baotin.threat.search.events.v1",publication(old,"old-view.example.com","2026-11-12T00:00:00Z",true));
        var run=manager.begin();Path manifest=manifest(run,List.of(publication(UUID.randomUUID(),"new-view.example.com","2026-11-12T00:00:00Z",true)));
        manager.resume(run.id(),manifest,100);
        var db=SearchServiceApplicationTests.DB;
        try(var connection=java.sql.DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var query=connection.createStatement()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try(var ignored=query.executeQuery("SELECT txid_current_snapshot()")) { assertTrue(ignored.next()); }
            manager.cutover(run.id());
            try(var rows=query.executeQuery("SELECT title FROM search.search_documents WHERE status='ACTIVE' AND title IS NOT NULL")) {
                assertTrue(rows.next());assertEquals("old-view.example.com",rows.getString(1));assertFalse(rows.next());
            }
            connection.rollback();
        }
        assertEquals("new-view.example.com",jdbc.queryForObject("SELECT title FROM search.search_documents WHERE title IS NOT NULL",String.class));
    }
    @Test void captureBarrierDrainsUncommittedLegacyWritersBeforeTakingTheJournalCursor() throws Exception {
        var db=SearchServiceApplicationTests.DB;
        try(var connection=java.sql.DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var statement=connection.prepareStatement("INSERT INTO search.search_documents(id,title,display_value,search_text,target_at,target_event,updated_at) VALUES (?,?,?,?,now(),?,now())")) {
            connection.setAutoCommit(false);statement.setObject(1,UUID.randomUUID());statement.setString(2,"uncommitted.example.com");statement.setString(3,"uncommitted.example.com");statement.setString(4,"uncommitted.example.com");statement.setObject(5,UUID.randomUUID());statement.executeUpdate();
            var beginning=java.util.concurrent.CompletableFuture.supplyAsync(manager::begin);
            Thread.sleep(100);assertFalse(beginning.isDone());connection.commit();
            var run=beginning.get(10,java.util.concurrent.TimeUnit.SECONDS);
            long sequence=jdbc.queryForObject("SELECT max(sequence) FROM search.projection_journal",Long.class);
            assertEquals(sequence,run.captureSequence());manager.cancel(run.id());
        }
    }
    @Test void ownerStateSnapshotsAreImportedWithoutFabricatingDomainChangeEvents() throws Exception {
        UUID ref=UUID.randomUUID();var run=manager.begin();
        var pub=Map.of("visibility","PUBLIC","documentType","URL","referenceId",ref.toString(),"title","owner.example.com","displayValue","owner.example.com","status","ACTIVE","metadata",Map.of());
        String target=json.writeValueAsString(Map.of("kind","OWNER_SNAPSHOT_V1","recordId",UUID.randomUUID().toString(),"source","threat-service","referenceId",ref.toString(),"documentType","URL","observedAt","2026-11-12T00:00:00Z","publicSearch",pub));
        String score=json.writeValueAsString(Map.of("kind","OWNER_SNAPSHOT_V1","recordId",UUID.randomUUID().toString(),"source","reputation-service","referenceId",ref.toString(),"documentType","URL","observedAt","2026-11-12T01:00:00Z","risk",Map.of("newScore",85,"newLevel","CRITICAL")));
        Path manifest=manifest(run,List.of(target,score));manager.resume(run.id(),manifest,100);manager.cutover(run.id());
        var result=find("owner.example.com").items().getFirst();assertEquals(ref,result.referenceId());assertEquals(85d,result.riskScore());
        assertThrows(IllegalArgumentException.class,()->ProjectionChange.ownerSnapshot(json.readTree(target.replace("threat-service","campaign-service"))));
    }
    @Test void declaredCountMismatchLeavesOnlyTheLastCommittedCheckpoint() throws Exception {
        UUID ref=UUID.randomUUID();projection.ingest("baotin.threat.search.events.v1",publication(ref,"retained.example.com","2026-11-12T00:00:00Z",true));
        var run=manager.begin();Path manifest=manifest(run,List.of(publication(UUID.randomUUID(),"staged.example.com","2026-11-12T00:00:00Z",true)));
        Files.writeString(manifest,Files.readString(manifest).replace("\"records\":1","\"records\":2"));
        assertThrows(IllegalArgumentException.class,()->manager.resume(run.id(),manifest,100));
        assertEquals(1,manager.status(run.id()).checkpoint());assertEquals("BUILDING",manager.status(run.id()).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search.search_documents WHERE reference_id=?",Integer.class,ref));
        assertThrows(IllegalStateException.class,()->manager.cutover(run.id()));
    }
    @Test void packagedOperatorCliRunsAndRejectsRuntimeCredentialsWithoutPrintingSecrets() throws Exception {
        Path password=dir.resolve("operator.password");Files.writeString(password,"FIXTURE-ONLY-migrator-password");
        var db=SearchServiceApplicationTests.DB;
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var command=new ProcessBuilder(executable,"-Xmx128m","-Dloader.main=com.baotin.search.SearchRebuild","-cp",System.getProperty("search.operator.jar"),"org.springframework.boot.loader.launch.PropertiesLauncher","begin");
        command.redirectErrorStream(true);command.environment().put("SEARCH_REBUILD_URL",db.getJdbcUrl());command.environment().put("SEARCH_REBUILD_USER","baotin_search_migrator");command.environment().put("SEARCH_REBUILD_PASSWORD_FILE",password.toString());
        var child=command.start();assertTrue(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,child.exitValue(),output);assertTrue(output.contains("BUILDING"));assertFalse(output.contains("FIXTURE-ONLY-migrator-password"));
        command.environment().put("SEARCH_REBUILD_USER","search_runtime_fixture");
        var denied=command.start();assertTrue(denied.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));String rejected=new String(denied.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(1,denied.exitValue());assertTrue(rejected.contains("SEARCH_REBUILD FAIL"));assertFalse(rejected.contains("FIXTURE-ONLY-migrator-password"));
    }
    Path ownerFile(RebuildManager.Run run,String source,List<String> rows) throws Exception {
        Path data=dir.resolve(source+".jsonl");Files.writeString(data,String.join("\n",rows)+(rows.isEmpty()?"":"\n"));
        Path manifest=Path.of(data+".manifest.json");
        Files.writeString(manifest,json.writeValueAsString(Map.of("schemaVersion",1,"source",source,"snapshotAt",run.startedAt().plusSeconds(1).toString(),"complete",true,"records",rows.size(),"dataFile",data.toString(),"sha256",RebuildManifest.digest(data))));
        return manifest;
    }
    @Test void composeVerifiedOwningExportsThenRebuildWithoutForeignSchemaReads() throws Exception {
        UUID ref=UUID.randomUUID();var run=manager.begin();
        var pub=Map.of("visibility","PUBLIC","documentType","URL","referenceId",ref.toString(),"title","composed.example.com","displayValue","composed.example.com","status","ACTIVE","metadata",Map.of());
        String target=json.writeValueAsString(Map.of("kind","OWNER_SNAPSHOT_V1","recordId",UUID.randomUUID().toString(),"source","threat-service","referenceId",ref.toString(),"documentType","URL","observedAt","2026-11-12T00:00:00Z","publicSearch",pub));
        String score=json.writeValueAsString(Map.of("kind","OWNER_SNAPSHOT_V1","recordId",UUID.randomUUID().toString(),"source","reputation-service","referenceId",ref.toString(),"documentType","URL","observedAt","2026-11-12T01:00:00Z","risk",Map.of("newScore",85,"newLevel","CRITICAL")));
        List<Path> inputs=List.of(ownerFile(run,"threat-service",List.of(target)),ownerFile(run,"reputation-service",List.of(score)),ownerFile(run,"campaign-service",List.of()));
        Path manifest=RebuildComposer.compose(run,inputs,dir.resolve("composed.json"));
        assertEquals(2,RebuildManifest.load(manifest,run.id(),run.startedAt()).records());
        manager.resume(run.id(),manifest,100);manager.cutover(run.id());
        assertEquals(85d,find("composed.example.com").items().getFirst().riskScore());
        assertThrows(IllegalArgumentException.class,()->RebuildComposer.compose(manager.status(run.id()),inputs,dir.resolve("again.json")));
    }
    @Test void compositionRejectsDuplicateOwnersChangedFilesAndMixedOwnership() throws Exception {
        var run=manager.begin();Path threat=ownerFile(run,"threat-service",List.of()),rep=ownerFile(run,"reputation-service",List.of()),camp=ownerFile(run,"campaign-service",List.of());
        assertThrows(IllegalArgumentException.class,()->RebuildComposer.compose(run,List.of(threat,threat,camp),dir.resolve("duplicate.json")));
        Files.writeString(dir.resolve("threat-service.jsonl"),"tampered");
        assertThrows(IllegalArgumentException.class,()->RebuildComposer.compose(run,List.of(threat,rep,camp),dir.resolve("changed.json")));
        assertFalse(Files.exists(dir.resolve("changed.json")));
    }
    @Test void missingSourceCompletenessAndConcurrentRebuildAreRejected() throws Exception {
        var run=manager.begin();assertThrows(org.springframework.dao.DataAccessException.class,()->manager.begin());
        Path file=manifest(run,List.of());Files.writeString(file,Files.readString(file).replace("\"complete\":true","\"complete\":false"));
        assertThrows(IllegalArgumentException.class,()->manager.resume(run.id(),file,100));
        assertEquals(0,manager.status(run.id()).checkpoint());
        assertThrows(IllegalStateException.class,()->manager.purge(run.id()));
        assertEquals("CANCELLED",manager.cancel(run.id()).status());manager.purge(run.id());
    }
}
