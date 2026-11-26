package com.baotin.reputation.operator;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

class SearchSnapshotExportTests {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @TempDir Path dir;
    @BeforeAll static void setup() throws Exception {
        DB.start();try(Connection c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());Statement s=c.createStatement()) {
            s.execute("CREATE ROLE baotin_reputation LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD 'FIXTURE-ONLY-export-password'; CREATE SCHEMA auth; CREATE TABLE auth.private_fixture(id int); CREATE SCHEMA reputation; CREATE TABLE reputation.target_reputation(threat_target_id uuid PRIMARY KEY,target_type text,final_score double precision,risk_level text,calculated_at timestamptz); GRANT USAGE ON SCHEMA reputation TO baotin_reputation; GRANT SELECT ON reputation.target_reputation TO baotin_reputation; INSERT INTO reputation.target_reputation VALUES ('20000000-0000-4000-8000-000000000001','URL',85,'CRITICAL',now()),('20000000-0000-4000-8000-000000000002','URL',0,'LOW',NULL),('20000000-0000-4000-8000-000000000003','PHONE',32,'MEDIUM',now())");
        }
    }
    @AfterAll static void stop() { DB.stop(); }
    Connection owner() throws SQLException { return DriverManager.getConnection(DB.getJdbcUrl(),"baotin_reputation","FIXTURE-ONLY-export-password"); }
    @Test void exportsActualCalculatedScoresWithoutInventedInitialLowOrDomainEvents() throws Exception {
        Path output=dir.resolve("reputation.jsonl");SearchSnapshotExport.Result result;
        try(Connection c=owner()) { result=SearchSnapshotExport.export(c,output,Instant.now().minusSeconds(5));assertTrue(c.isReadOnly()); }
        assertEquals(2,result.records());String data=Files.readString(output);
        assertTrue(data.contains("OWNER_SNAPSHOT_V1"));assertTrue(data.contains("85.0"));assertTrue(data.contains("CRITICAL"));
        assertFalse(data.contains("eventType"));assertFalse(data.contains("oldScore"));assertFalse(data.contains("reason"));
        assertFalse(data.contains("20000000-0000-4000-8000-000000000002"));
    }
    @Test void wrongSourceRoleAndFutureSnapshotCannotProduceCompleteExports() throws Exception {
        try(Connection c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            assertThrows(IllegalArgumentException.class,()->SearchSnapshotExport.export(c,dir.resolve("wrong.jsonl"),Instant.now().minusSeconds(5)));
        }
        try(Connection c=owner()) {
            assertThrows(IllegalArgumentException.class,()->SearchSnapshotExport.export(c,dir.resolve("future.jsonl"),Instant.now().plusSeconds(600)));
        }
        assertFalse(Files.exists(dir.resolve("wrong.jsonl.manifest.json")));assertFalse(Files.exists(dir.resolve("future.jsonl.manifest.json")));
    }
    @Test void owningRoleCannotReadAnotherBusinessSchemaOrCreateTables() throws Exception {
        try(Connection c=owner();Statement s=c.createStatement()) {
            assertThrows(SQLException.class,()->s.execute("SELECT * FROM auth.private_fixture"));
            assertThrows(SQLException.class,()->s.execute("CREATE TABLE public.forbidden_export(id int)"));
        }
    }
}
