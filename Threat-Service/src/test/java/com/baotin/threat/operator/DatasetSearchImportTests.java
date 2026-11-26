package com.baotin.threat.operator;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

class DatasetSearchImportTests {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @TempDir Path dir;
    @BeforeAll static void prepare() throws Exception {
        DB.start();
        org.flywaydb.core.Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas("threat").defaultSchema("threat").load().migrate();
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("CREATE ROLE baotin_threat LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD 'FIXTURE-ONLY-dataset-password';GRANT USAGE ON SCHEMA threat TO baotin_threat;GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA threat TO baotin_threat");
        }
    }
    @BeforeEach void clear() throws Exception {
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("TRUNCATE threat.dataset_import_rows,threat.public_message_templates,threat.targets,threat.outbox_events CASCADE");
        }
    }
    @AfterAll static void stop() { DB.stop(); }
    @Test void importsActualMaskedTemplatesAndUrlOutboxIdempotently() throws Exception {
        Path data=dir.resolve("input.jsonl");
        Files.writeString(data,"{\"type\":\"TEXT\",\"value\":\"Cảnh báo khóa tài khoản! Nhập OTP 123456 tại https://example.com/login?token=SECRET, liên hệ 0901234567 hoặc alice@example.com\",\"label\":\"SUSPICIOUS\"}\n{\"type\":\"URL\",\"value\":\"https://dataset.example.com/\",\"label\":\"SUSPICIOUS\"}\n");
        Path password=dir.resolve("password");Files.writeString(password,"FIXTURE-ONLY-dataset-password");
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(data)));
        String first=run(data,password,digest);assertTrue(first.contains("inserted=2"),first);
        String second=run(data,password,digest);assertTrue(second.contains("inserted=0"),second);
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT redacted_text FROM threat.public_message_templates")) {
                assertTrue(r.next());String value=r.getString(1);assertTrue(value.contains("Cảnh báo khóa tài khoản"));assertTrue(value.contains("***"));
                for(String raw:List.of("123456","0901234567","alice@example.com","SECRET"))assertFalse(value.contains(raw));
            }
            try(var r=s.executeQuery("SELECT count(*) FROM threat.outbox_events WHERE payload->'payload'->>'targetType'='URL' AND payload->'payload'->'publicSearch'->'metadata'->>'contentWarning'='SUSPICIOUS'")){r.next();assertEquals(1,r.getInt(1));}
            try(var r=s.executeQuery("SELECT count(*) FROM threat.dataset_import_rows")){r.next();assertEquals(2,r.getInt(1));}
            try(var r=s.executeQuery("SELECT string_agg(payload::text,' ') FROM threat.outbox_events")){r.next();assertFalse(r.getString(1).contains("alice@example.com"));assertFalse(r.getString(1).contains("0901234567"));}
        }
    }
    @Test void pendingOutboxHasAnOrderedPartialIndexWithoutNewRuntimeDdlRights() throws Exception {
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT pg_get_indexdef(indexrelid),indisvalid,indisready FROM pg_index WHERE indexrelid='threat.idx_outbox_pending_created_at'::regclass")) {
                assertTrue(r.next());assertTrue(r.getString(1).contains("(created_at, id)"));assertTrue(r.getString(1).contains("PENDING"));assertTrue(r.getBoolean(2));assertTrue(r.getBoolean(3));
            }
            s.execute("SET ROLE baotin_threat");
            try(var r=s.executeQuery("SELECT has_schema_privilege(current_user,'threat','CREATE')")){r.next();assertFalse(r.getBoolean(1));}
        }
    }
    @Test void benignLabelsAreStoredAndPublishedForMessagesAndUrls() throws Exception {
        Path data=dir.resolve("benign.jsonl");
        Files.writeString(data,"{\"type\":\"TEXT\",\"value\":\"Hẹn gặp bạn vào chiều mai nhé.\",\"label\":\"0\"}\n{\"type\":\"URL\",\"value\":\"https://normal.example.com/\",\"label\":\"benign\"}\n");
        Path password=dir.resolve("benign-password");Files.writeString(password,"FIXTURE-ONLY-dataset-password");
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(data)));
        assertTrue(run(data,password,digest).contains("inserted=2"));assertTrue(run(data,password,digest).contains("inserted=0"));
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT count(*) FROM threat.dataset_import_rows WHERE dataset_label='BENIGN'")){r.next();assertEquals(2,r.getInt(1));}
            try(var r=s.executeQuery("SELECT count(*) FROM threat.outbox_events WHERE payload->'payload'->'publicSearch'->'metadata'->>'contentWarning'='BENIGN'")){r.next();assertTrue(r.getInt(1)>=2);}
        }
    }
    String run(Path input,Path password,String hash) throws Exception {
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx128m","-Dloader.main=com.baotin.threat.operator.DatasetSearchImport","-cp",System.getProperty("threat.operator.jar"),"org.springframework.boot.loader.launch.PropertiesLauncher","fixture-v1",input.toString(),hash);
        p.environment().put("DATASET_DB_URL",DB.getJdbcUrl());p.environment().put("DATASET_DB_USER","baotin_threat");p.environment().put("DATASET_DB_PASSWORD_FILE",password.toString());p.redirectErrorStream(true);
        var child=p.start();assertTrue(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,child.exitValue(),output);assertFalse(output.contains("FIXTURE-ONLY-dataset-password"));return output;
    }
}
