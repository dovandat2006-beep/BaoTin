package com.baotin.report.operator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** Operator-only entry point; runtime never receives migration credentials. */
public final class DatabaseMigration {
    private DatabaseMigration() {}
    public static void main(String[] args) {
        String runtime=required("DB_RUNTIME_USER");
        String migrator=required("DB_MIGRATION_USER");
        if(!runtime.matches("[a-z_][a-z0-9_]{0,62}") || runtime.equals(migrator))throw new IllegalArgumentException("Separate valid runtime role required");
        String passwordFile=required("DB_MIGRATION_PASSWORD_FILE");
        String password;
        try {var path=Path.of(passwordFile);if(!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path)>4096)throw new IllegalArgumentException();password=Files.readString(path,StandardCharsets.UTF_8).trim();}
        catch(Exception e){throw new IllegalArgumentException("Bounded regular migration password file required");}
        if(password.length()<32)throw new IllegalArgumentException("Migration password file must contain a strong credential");
        var flyway=Flyway.configure().dataSource(required("DB_URL"),migrator,password)
            .defaultSchema("report").schemas("report").locations("classpath:db/migration").load();
        flyway.migrate();
        try(var connection=flyway.getConfiguration().getDataSource().getConnection();var statement=connection.createStatement()) {
            statement.execute("GRANT USAGE ON SCHEMA report TO "+runtime);
            statement.execute("GRANT SELECT,INSERT,UPDATE ON report.reports,report.evidence,report.idempotency_keys,report.outbox_events TO "+runtime);
            statement.execute("REVOKE DELETE ON report.reports,report.evidence,report.idempotency_keys,report.outbox_events FROM "+runtime);
            statement.execute("GRANT SELECT,INSERT ON report.status_history TO "+runtime);
            statement.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA report TO "+runtime);
            statement.execute("REVOKE ALL ON report.flyway_schema_history FROM "+runtime);
        }catch(java.sql.SQLException e){throw new IllegalStateException("Runtime grants failed");}
        System.out.println("BUSINESS_MIGRATION PASS schema=report runtime=separate history_write=denied");
    }
    private static String required(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalArgumentException("Missing migration setting: "+name);return value;}
}
