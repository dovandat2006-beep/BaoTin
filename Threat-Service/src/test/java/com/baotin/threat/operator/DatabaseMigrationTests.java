package com.baotin.threat.operator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationTests {
    @TempDir Path directory;
    private static final String FIXTURE_PASSWORD = "TEST-FIXTURE-ONLY-strong-migration-password";

    @Test void boundedRegularSecretFileIsReadWithoutEnvironmentPassword() throws Exception {
        Path file = directory.resolve("migration.password");
        Files.writeString(file, FIXTURE_PASSWORD + "\n");
        assertEquals(FIXTURE_PASSWORD, DatabaseMigration.migrationPassword(Map.of("DB_MIGRATION_PASSWORD_FILE", file.toString())));
    }
    @Test void legacyDirectEnvironmentInputRemainsCompatible() {
        assertEquals(FIXTURE_PASSWORD, DatabaseMigration.migrationPassword(Map.of("DB_MIGRATION_PASSWORD", FIXTURE_PASSWORD)));
    }
    @Test void ambiguousSourcesAndMissingCredentialsFailWithoutDisclosingValues() throws Exception {
        Path file = directory.resolve("migration.password"); Files.writeString(file, FIXTURE_PASSWORD);
        var failure = assertThrows(IllegalArgumentException.class, () -> DatabaseMigration.migrationPassword(Map.of("DB_MIGRATION_PASSWORD_FILE", file.toString(), "DB_MIGRATION_PASSWORD", FIXTURE_PASSWORD)));
        assertFalse(failure.getMessage().contains(FIXTURE_PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> DatabaseMigration.migrationPassword(Map.of()));
    }
    @Test void weakOversizeDirectoryAndMissingFileAreRejected() throws Exception {
        Path weak = directory.resolve("weak.password"); Files.writeString(weak, "short");
        Path large = directory.resolve("large.password"); Files.writeString(large, "x".repeat(4097));
        for (Path file : new Path[]{weak, large, directory, directory.resolve("absent.password")}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> DatabaseMigration.migrationPassword(Map.of("DB_MIGRATION_PASSWORD_FILE", file.toString())));
            assertFalse(failure.getMessage().contains(file.toString()));
        }
    }
    @Test void runtimeCannotBeMigratorOrAnSqlIdentifierPayload() {
        assertDoesNotThrow(() -> DatabaseMigration.validateRuntime("baotin_threat_migrator", "baotin_threat"));
        assertDoesNotThrow(() -> DatabaseMigration.validateRuntime("baotin_threat_migrator", null));
        assertThrows(IllegalArgumentException.class, () -> DatabaseMigration.validateRuntime("baotin_threat_migrator", "baotin_threat_migrator"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseMigration.validateRuntime("baotin_threat_migrator", "runtime; DROP TABLE threat.targets"));
    }
}
