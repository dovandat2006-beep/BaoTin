package com.baotin.reputation.operator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Read-only owning-source export. No foreign business schema or fake domain events. */
public final class SearchSnapshotExport {
    private static final String OWNER="reputation-service";
    private static final String ROLE="baotin_reputation";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Result(int schemaVersion,String source,String snapshotAt,boolean complete,long records,String dataFile,String sha256) {}
    private SearchSnapshotExport() {}
    public static void main(String[] args) {
        try {
            if(args.length!=2)throw new IllegalArgumentException("Output path and begin timestamp required");
            String url=System.getenv("SOURCE_EXPORT_URL"),user=System.getenv("SOURCE_EXPORT_USER");
            if(url==null || !url.startsWith("jdbc:postgresql://") || !ROLE.equals(user))throw new IllegalArgumentException("Owning source role required");
            Path password=Path.of(System.getenv("SOURCE_EXPORT_PASSWORD_FILE"));regular(password,4096);
            try(Connection connection=DriverManager.getConnection(url,user,Files.readString(password).strip())) {
                Result result=export(connection,Path.of(args[0]),Instant.parse(args[1]));
                System.out.println(JSON.writeValueAsString(result));
            }
        } catch(Exception ignored) {
            System.err.println("OWNER_SEARCH_EXPORT FAIL owner=reputation-service input_role_schema_or_snapshot_check; payload/password withheld");System.exit(1);
        }
    }
    public static Result export(Connection connection,Path output,Instant notBefore) throws Exception {
        output=output.toAbsolutePath().normalize();Path parent=output.getParent();
        if(parent==null || !Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().equals(parent)
                || Files.exists(output,LinkOption.NOFOLLOW_LINKS) || Files.exists(Path.of(output+".manifest.json"),LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("New regular output required");
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setReadOnly(true);connection.setAutoCommit(false);
        Instant snapshot;
        try(Statement statement=connection.createStatement();ResultSet identity=statement.executeQuery("SELECT current_user,transaction_timestamp()")) {
            if(!identity.next() || !ROLE.equals(identity.getString(1)))throw new IllegalArgumentException("Owning source identity required");
            snapshot=identity.getTimestamp(2).toInstant();
        }
        if(snapshot.isBefore(notBefore))throw new IllegalArgumentException("Source snapshot predates rebuild begin");

        MessageDigest digest=MessageDigest.getInstance("SHA-256");long records;
        try(OutputStream raw=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW);
            DigestOutputStream hashed=new DigestOutputStream(raw,digest);
            BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(hashed,StandardCharsets.UTF_8))) {
            records=write(connection,writer);
        }
        connection.commit();
        Result result=new Result(1,OWNER,snapshot.toString(),true,records,output.toString(),HexFormat.of().formatHex(digest.digest()));
        Files.writeString(Path.of(output+".manifest.json"),JSON.writeValueAsString(result)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        return result;
    }
    private static long write(Connection connection,BufferedWriter writer) throws Exception {
        long records=0;
        try(PreparedStatement query=connection.prepareStatement("SELECT threat_target_id,target_type,final_score,risk_level,calculated_at FROM reputation.target_reputation WHERE calculated_at IS NOT NULL ORDER BY threat_target_id")) {
            query.setFetchSize(500);
            try(ResultSet targets=query.executeQuery()) {
                while(targets.next()) {
                    String type=targets.getString(2);if(type.equals("MESSAGE_TEMPLATE"))type="OTHER";
                    if(!Set.of("URL","PHONE","BANK_ACCOUNT","EMAIL","OTHER").contains(type))throw new IllegalArgumentException("Unsupported owner target");
                    double score=targets.getDouble(3);String level=targets.getString(4);
                    if(!Double.isFinite(score) || score<0 || score>100 || !Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(level))throw new IllegalArgumentException("Invalid owning score");
                    Map<String,Object> row=row(targets.getObject(1,UUID.class),type,targets.getTimestamp(5).toInstant());
                    row.put("risk",Map.of("newScore",score,"newLevel",level));writer.write(JSON.writeValueAsString(row));writer.newLine();
                    if(++records>1_000_000)throw new IllegalStateException("Snapshot exceeds bounded export");
                }
            }
        }
        return records;
    }
    private static Map<String,Object> row(UUID reference,String type,Instant observed) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("kind","OWNER_SNAPSHOT_V1");
        // Stable source-state identity, not a security digest or fabricated event ID.
        row.put("recordId",UUID.nameUUIDFromBytes((OWNER+":"+reference+":"+observed).getBytes(StandardCharsets.UTF_8)));
        row.put("source",OWNER);row.put("referenceId",reference);row.put("documentType",type);row.put("observedAt",observed.toString());return row;
    }
    private static void regular(Path path,long max) throws IOException {
        if(!path.isAbsolute() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                || !path.toRealPath().equals(path.toAbsolutePath().normalize()) || Files.size(path)>max)throw new IllegalArgumentException("Protected regular input required");
    }
}
