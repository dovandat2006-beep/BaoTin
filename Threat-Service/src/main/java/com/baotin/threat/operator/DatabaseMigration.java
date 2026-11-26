package com.baotin.threat.operator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** Isolated operator entry point: migration credentials never enter the runtime service. */
public final class DatabaseMigration {
    private DatabaseMigration() {}
    public static void main(String[] args) {
        String migrator = required("DB_MIGRATION_USER");
        String runtime = System.getenv("DB_RUNTIME_USER");
        validateRuntime(migrator, runtime);
        var flyway = Flyway.configure().dataSource(required("DB_URL"), migrator, migrationPassword(System.getenv()))
                .defaultSchema("threat").schemas("threat").locations("classpath:db/migration").load();
        flyway.migrate();
        if (runtime != null && !runtime.isBlank()) {
            try (var connection = flyway.getConfiguration().getDataSource().getConnection(); var statement = connection.createStatement()) {
                statement.execute("GRANT USAGE ON SCHEMA threat TO \"" + runtime + "\"");
                statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON threat.targets,threat.scan_history,threat.scan_urls,threat.outbox_events,threat.rate_limits TO \"" + runtime + "\"");
                statement.execute("GRANT SELECT,INSERT,UPDATE ON threat.scan_metrics_daily,threat.public_message_templates,threat.dataset_import_rows TO \"" + runtime + "\"");
                statement.execute("REVOKE DELETE ON threat.scan_metrics_daily FROM \"" + runtime + "\"");
                statement.execute("REVOKE ALL ON threat.flyway_schema_history FROM \"" + runtime + "\"");
            } catch (java.sql.SQLException e) { throw new IllegalStateException("Cannot grant runtime schema privileges", e); }
        }
        System.out.println("BUSINESS_MIGRATION PASS schema=threat runtime=" +
                (runtime == null || runtime.isBlank() ? "not-requested" : "separate") + " history_write=denied");
    }
    static void validateRuntime(String migrator, String runtime) {
        if (runtime != null && !runtime.isBlank()
                && (!runtime.matches("[a-z_][a-z0-9_]{0,62}") || runtime.equals(migrator))) {
            throw new IllegalArgumentException("Separate valid runtime role required");
        }
    }
    static String migrationPassword(Map<String, String> environment) {
        String value = environment.get("DB_MIGRATION_PASSWORD");
        String file = environment.get("DB_MIGRATION_PASSWORD_FILE");
        if (file == null || file.isBlank()) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Migration password or its file required");
            return value;
        }
        if (value != null && !value.isBlank()) throw new IllegalArgumentException("Use only one migration password source");
        try {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path) > 4096) {
                throw new IllegalArgumentException();
            }
            String password = Files.readString(path, StandardCharsets.UTF_8).stripTrailing();
            if (password.length() < 32) throw new IllegalArgumentException();
            return password;
        } catch (Exception e) {
            throw new IllegalArgumentException("Bounded regular strong migration password file required");
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing migration setting: " + name);
        return value;
    }
}
