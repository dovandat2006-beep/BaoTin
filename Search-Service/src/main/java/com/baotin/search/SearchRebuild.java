package com.baotin.search;

import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Manual CLI only; use the owning operator credential, never a client token/header. */
public final class SearchRebuild {
    private SearchRebuild() {}
    public static void main(String[] args) {
        try { execute(args); }
        catch(Exception ignored) { System.err.println("SEARCH_REBUILD FAIL input_role_state_or_database_check; no secret/payload diagnostics emitted");System.exit(1); }
    }
    private static void execute(String[] args) throws Exception {
        if(args.length<1)throw new IllegalArgumentException("Operation required");
        String url=System.getenv("SEARCH_REBUILD_URL"),user=System.getenv("SEARCH_REBUILD_USER");
        if(url==null || !url.startsWith("jdbc:postgresql://") || !"baotin_search_migrator".equals(user))throw new IllegalArgumentException("Owning operator required");
        Path password=Path.of(System.getenv("SEARCH_REBUILD_PASSWORD_FILE"));RebuildManifest.regular(password,4096);
        var source=new SingleConnectionDataSource(url,user,Files.readString(password).strip(),true);
        var manager=new RebuildManager(new JdbcTemplate(source));Object result;
        if(args[0].equals("begin") && args.length==1) result=manager.begin();
        else {
            if(args.length<2)throw new IllegalArgumentException("Run required");UUID id=UUID.fromString(args[1]);
            result=switch(args[0]) {
                case "compose" -> { require(args.length==6);Path file=RebuildComposer.compose(manager.status(id),List.of(Path.of(args[2]),Path.of(args[3]),Path.of(args[4])),Path.of(args[5]));yield Map.of("id",id,"manifest",file.toString()); }
                case "status" -> { require(args.length==2);yield manager.status(id); }
                case "resume" -> { require(args.length==3 || args.length==4);yield manager.resume(id,Path.of(args[2]),args.length==4?Integer.parseInt(args[3]):1000); }
                case "cutover" -> { require(args.length==2);yield manager.cutover(id); }
                case "cancel" -> { require(args.length==2);yield manager.cancel(id); }
                case "purge" -> { require(args.length==2);manager.purge(id);yield Map.of("id",id,"stagingPurged",true); }
                default -> throw new IllegalArgumentException("Unknown operation");
            };
        }
        System.out.println(JsonMapper.builder().build().writeValueAsString(result));
    }
    private static void require(boolean condition) { if(!condition)throw new IllegalArgumentException("Invalid arguments"); }
}
