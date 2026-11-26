package com.baotin.search;

import java.nio.file.*;
import org.flywaydb.core.Flyway;

/** Explicit operator entry point; never invoked by the runtime server. */
public final class SearchMigration {
    private SearchMigration() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments accepted");
        String url=System.getenv("SEARCH_MIGRATION_URL"), user=System.getenv("SEARCH_MIGRATION_USER");
        if (url==null || !url.startsWith("jdbc:postgresql://") || !"baotin_search_migrator".equals(user))
            throw new IllegalArgumentException("Explicit Search migrator identity required");
        Path path=Path.of(System.getenv("SEARCH_MIGRATION_PASSWORD_FILE"));
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>4096)
            throw new IllegalArgumentException("External regular password file required");
        Flyway.configure().dataSource(url,user,Files.readString(path).strip()).schemas("search").defaultSchema("search")
            .createSchemas(false).locations("classpath:db/migration").load().migrate();
        System.out.println("SEARCH_MIGRATION PASS schema=search");
    }
}
