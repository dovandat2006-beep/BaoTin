package com.baotin.search;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Composition of verified owner files only; never connects to an owning service DB. */
final class RebuildComposer {
    private record Owner(String name,Instant at,Path data,String hash,long records) {}
    private RebuildComposer() {}
    static Path compose(RebuildManager.Run run,List<Path> manifests,Path output) throws Exception {
        if(!run.status().equals("BUILDING") || run.checkpoint()!=0 || manifests.size()!=3)throw invalid();
        List<Owner> owners=new ArrayList<>();Set<String> names=new HashSet<>();long total=0;
        for(Path path:manifests) {
            Owner owner=owner(path,run.startedAt());if(!names.add(owner.name()))throw invalid();
            owners.add(owner);total+=owner.records();
        }
        if(!names.equals(Set.of("threat-service","reputation-service","campaign-service")) || total>1_000_000)throw invalid();
        output=output.toAbsolutePath().normalize();Path parent=output.getParent(),data=Path.of(output+".jsonl");
        if(parent==null || !Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().equals(parent)
                || Files.exists(output,LinkOption.NOFOLLOW_LINKS) || Files.exists(data,LinkOption.NOFOLLOW_LINKS))throw invalid();
        long records=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(OutputStream raw=Files.newOutputStream(data,StandardOpenOption.CREATE_NEW);DigestOutputStream hashed=new DigestOutputStream(raw,digest);
                BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(hashed,StandardCharsets.UTF_8))) {
            for(Owner owner:owners) {
                long count=0;
                try(Reader reader=Files.newBufferedReader(owner.data(),StandardCharsets.UTF_8)) {
                    String line;
                    while((line=RebuildManifest.line(reader))!=null) {
                        JsonNode record=RebuildManifest.JSON.readTree(line);
                        if(!record.path("kind").asString().equals("OWNER_SNAPSHOT_V1") || !Projection.text(record,"source").equals(owner.name()))throw invalid();
                        ProjectionChange.ownerSnapshot(record);writer.write(line);writer.write('\n');count++;records++;
                        if(count>owner.records() || records>1_000_000)throw invalid();
                    }
                }
                if(count!=owner.records() || !RebuildManifest.digest(owner.data()).equals(owner.hash()))throw invalid();
            }
        }
        if(Files.size(data)>512L*1024*1024)throw invalid();
        List<Map<String,Object>> sources=owners.stream().map(o->Map.<String,Object>of("source",o.name(),"snapshotAt",o.at().toString(),"complete",true)).toList();
        Map<String,Object> result=Map.of("schemaVersion",1,"runId",run.id(),"dataFile",data.toString(),"sha256",HexFormat.of().formatHex(digest.digest()),"records",records,"sources",sources);
        Files.writeString(output,RebuildManifest.JSON.writeValueAsString(result)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        RebuildManifest.load(output,run.id(),run.startedAt());return output;
    }
    private static Owner owner(Path path,Instant started) throws IOException {
        RebuildManifest.regular(path,16384);JsonNode m=RebuildManifest.JSON.readTree(Files.readAllBytes(path));
        if(!m.path("schemaVersion").isIntegralNumber() || m.path("schemaVersion").asInt()!=1 || !m.path("complete").isBoolean() || !m.path("complete").asBoolean()
                || !m.path("records").isIntegralNumber())throw invalid();
        String name=Projection.text(m,"source");if(!Set.of("threat-service","reputation-service","campaign-service").contains(name))throw invalid();
        Instant at=Instant.parse(Projection.text(m,"snapshotAt"));if(at.isBefore(started) || at.isAfter(Instant.now().plusSeconds(300)))throw invalid();
        long count=m.path("records").asLong();if(count<0 || count>1_000_000)throw invalid();
        Path data=Path.of(Projection.text(m,"dataFile"));RebuildManifest.regular(data,512L*1024*1024);
        String hash=Projection.text(m,"sha256");if(!RebuildManifest.digest(data).equals(hash))throw invalid();
        return new Owner(name,at,data,hash,count);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Owner export set/count/hash/state is invalid"); }
}
