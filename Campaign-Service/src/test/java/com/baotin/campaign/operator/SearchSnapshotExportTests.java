package com.baotin.campaign.operator;

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
            s.execute("CREATE ROLE baotin_campaign_exporter LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD 'FIXTURE-ONLY-export-password'; CREATE SCHEMA auth; CREATE TABLE auth.private_fixture(id int); CREATE SCHEMA campaign; REVOKE ALL ON SCHEMA campaign FROM PUBLIC");
        }
    }
    @AfterAll static void stop() { DB.stop(); }
    Connection owner() throws SQLException { return DriverManager.getConnection(DB.getJdbcUrl(),"baotin_campaign_exporter","FIXTURE-ONLY-export-password"); }
    @Test void scaffoldCanDeclareEmptyOnlyAfterItsOwningNamespaceHasNoPersistedRelations() throws Exception {
        Path output=dir.resolve("campaign.jsonl");SearchSnapshotExport.Result result;
        try(Connection c=owner()) { result=SearchSnapshotExport.export(c,output,Instant.now().minusSeconds(5)); }
        assertEquals(0,result.records());assertEquals(0,Files.size(output));assertTrue(result.complete());
        try(Connection c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());Statement s=c.createStatement()) {
            s.execute("CREATE TABLE campaign.unsupported_business(id int)");
            try(Connection limited=owner()) { assertThrows(IllegalStateException.class,()->SearchSnapshotExport.export(limited,dir.resolve("unsupported.jsonl"),Instant.now().minusSeconds(5))); }
            s.execute("DROP TABLE campaign.unsupported_business");
        }
        assertFalse(Files.exists(dir.resolve("unsupported.jsonl.manifest.json")));
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
