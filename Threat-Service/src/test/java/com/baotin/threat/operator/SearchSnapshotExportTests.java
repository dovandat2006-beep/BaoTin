package com.baotin.threat.operator;

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
            s.execute("CREATE ROLE baotin_threat LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD 'FIXTURE-ONLY-export-password'; CREATE SCHEMA auth; CREATE TABLE auth.private_fixture(id int); CREATE SCHEMA threat; CREATE TABLE threat.targets(id uuid PRIMARY KEY,type text,canonical_hash text,normalized_value text,updated_at timestamptz NOT NULL DEFAULT now()); CREATE TABLE threat.dataset_import_rows(target_id uuid,dataset_label text); GRANT SELECT ON threat.dataset_import_rows TO baotin_threat; CREATE TABLE threat.public_message_templates(target_id uuid,redacted_text text,dataset_label text); GRANT SELECT ON threat.public_message_templates TO baotin_threat; GRANT USAGE ON SCHEMA threat TO baotin_threat; GRANT SELECT ON threat.targets TO baotin_threat; INSERT INTO threat.targets(id,type,canonical_hash,normalized_value) VALUES ('10000000-0000-4000-8000-000000000001','URL','hash','https://export.example.com/private?token=PRIVATE-TOKEN'),('10000000-0000-4000-8000-000000000002','MESSAGE_TEMPLATE','123456abcdef0000000000000000000000000000000000000000000000000000',NULL),('10000000-0000-4000-8000-000000000003','URL','private','http://127.0.0.1/private')");
        }
    }
    @AfterAll static void stop() { DB.stop(); }
    Connection owner() throws SQLException { return DriverManager.getConnection(DB.getJdbcUrl(),"baotin_threat","FIXTURE-ONLY-export-password"); }
    @Test void exportsOnlyHostnamesAndFingerprintsFromTheOwningReadonlyTransaction() throws Exception {
        Path output=dir.resolve("threat.jsonl");SearchSnapshotExport.Result result;
        try(Connection c=owner()) { result=SearchSnapshotExport.export(c,output,Instant.now().minusSeconds(5));assertTrue(c.isReadOnly()); }
        assertTrue(result.complete());assertEquals(2,result.records());assertEquals("threat-service",result.source());
        String data=Files.readString(output);assertTrue(data.contains("export.example.com"));assertTrue(data.contains("Mẫu tin nhắn #123456abcdef"));
        assertFalse(data.contains("PRIVATE-TOKEN"));assertFalse(data.contains("/private"));assertFalse(data.contains("127.0.0.1"));
        assertEquals(2,data.lines().count());assertTrue(Files.exists(Path.of(output+".manifest.json")));
        try(Connection c=owner()) { assertThrows(IllegalArgumentException.class,()->SearchSnapshotExport.export(c,output,Instant.now().minusSeconds(5))); }
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
