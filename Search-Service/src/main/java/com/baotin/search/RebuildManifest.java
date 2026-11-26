package com.baotin.search;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

record RebuildManifest(Path data,String manifestHash,String fileHash,long records) {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static RebuildManifest load(Path path,UUID run,Instant started) throws IOException {
        regular(path,16384);byte[] bytes=Files.readAllBytes(path);JsonNode m=JSON.readTree(bytes);
        if(!m.path("schemaVersion").isIntegralNumber() || m.path("schemaVersion").asInt()!=1 || !UUID.fromString(Projection.text(m,"runId")).equals(run)
                || !m.path("records").isIntegralNumber()) throw invalid();
        long records=m.path("records").asLong();if(records<0 || records>1_000_000) throw invalid();
        String hash=Projection.text(m,"sha256");
        if(hash.length()!=64 || !hash.chars().allMatch(c->c>='0'&&c<='9'||c>='a'&&c<='f')) throw invalid();
        JsonNode sources=m.path("sources");if(!sources.isArray() || sources.size()!=3)throw invalid();
        Set<String> names=new HashSet<>();
        for(JsonNode source:sources) {
            String name=Projection.text(source,"source");
            if(!Set.of("threat-service","reputation-service","campaign-service").contains(name) || !names.add(name)
                    || !source.path("complete").isBoolean() || !source.path("complete").asBoolean()) throw invalid();
            Instant snapshot=Instant.parse(Projection.text(source,"snapshotAt"));
            if(snapshot.isBefore(started) || snapshot.isAfter(Instant.now().plusSeconds(300))) throw invalid();
        }
        Path data=Path.of(Projection.text(m,"dataFile"));regular(data,512L*1024*1024);
        if(!digest(data).equals(hash)) throw invalid();
        return new RebuildManifest(data,sha(bytes),hash,records);
    }
    static void regular(Path path,long max) throws IOException {
        if(!path.isAbsolute() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
            || !path.toRealPath().equals(path.toAbsolutePath().normalize()) || Files.size(path)>max) throw invalid();
    }
    static String digest(Path path) throws IOException {
        MessageDigest md=algorithm();try(InputStream in=Files.newInputStream(path)) {
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)md.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(algorithm().digest(bytes)); }
    private static MessageDigest algorithm() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 required",e); }
    }
    static String line(Reader reader) throws IOException {
        StringBuilder value=new StringBuilder();int c;
        while((c=reader.read())!=-1 && c!='\n') {
            if(value.length()>=65536)throw invalid();value.append((char)c);
        }
        if(c==-1 && value.isEmpty()) return null;
        if(!value.isEmpty() && value.charAt(value.length()-1)=='\r') value.setLength(value.length()-1);
        if(value.isEmpty())throw invalid();return value.toString();
    }
    static ProjectionChange change(String line) {
        JsonNode row=JSON.readTree(line);
        if(row.path("kind").isString())return ProjectionChange.ownerSnapshot(row);
        if(!row.path("event").isObject())throw invalid();
        return ProjectionChange.parse(Projection.text(row,"topic"),JSON.writeValueAsString(row.path("event")));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid or changed rebuild snapshot manifest/input"); }
}
