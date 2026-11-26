package com.baotin.analytics.operator;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** Invoked offline by the operator, never by the HTTP runtime. */
public final class DatabaseMigration {
    private DatabaseMigration() {}

    public static void main(String[] args) throws Exception {
        String url = System.getenv("DB_URL");
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PostgreSQL deployment URL required");
        }
        Path passwordFile = Path.of(System.getenv("DB_MIGRATION_PASSWORD_FILE"));
        if (!passwordFile.isAbsolute() || !Files.isRegularFile(passwordFile)
                || Files.isSymbolicLink(passwordFile) || Files.size(passwordFile) > 4096) {
            throw new IllegalArgumentException("Bounded regular external migration password file required");
        }
        String password = Files.readString(passwordFile).strip();
        if (password.length() < 32) {
            throw new IllegalArgumentException("Strong migration password required");
        }
        var flyway = Flyway.configure().dataSource(url, "baotin_analytics_migrator", password)
                .schemas("analytics").defaultSchema("analytics").createSchemas(false).load();
        flyway.migrate();
        flyway.validate();
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("GRANT SELECT, INSERT ON analytics.inbox_events, analytics.processed_scans, analytics.known_targets TO baotin_analytics");
            statement.execute("GRANT SELECT, INSERT, UPDATE ON analytics.daily_metrics, analytics.report_lifecycle_state, analytics.target_reputation_view, analytics.backfill_checkpoints, analytics.backfill_partition_checkpoints TO baotin_analytics");
            connection.commit();
        }
        System.out.println("ANALYTICS_MIGRATION PASS schema=analytics runtime=separate");
    }
}
