package com.baotin.threat.operator;

import com.baotin.threat.domain.TargetIdentity;
import com.baotin.threat.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Manual owner-only dataset seed, no user identity/report VERIFIED or model result is fabricated. */
public final class DatasetSearchImport {
    private DatasetSearchImport() {}
    public static void main(String[] args) {
        try {
            if(args.length!=3 || !args[0].matches("[a-z0-9-]{1,80}"))throw new IllegalArgumentException("Dataset name/file/SHA required");
            Path input=Path.of(args[1]);if(!input.isAbsolute() || !Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS) || Files.size(input)>128L*1024*1024)throw new IllegalArgumentException("Bounded regular dataset required");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
            if(!digest.equals(args[2]))throw new IllegalArgumentException("Dataset changed");
            if(!"baotin_threat".equals(System.getenv("DATASET_DB_USER")))throw new IllegalArgumentException("Owning runtime identity required");
            Path secret=Path.of(System.getenv("DATASET_DB_PASSWORD_FILE"));if(!Files.isRegularFile(secret,LinkOption.NOFOLLOW_LINKS)||Files.size(secret)>4096)throw new IllegalArgumentException("External password file required");
            var data=new SingleConnectionDataSource(System.getenv("DATASET_DB_URL"),"baotin_threat",Files.readString(secret).strip(),true);
            var jdbc=new JdbcTemplate(data);if(!"baotin_threat".equals(jdbc.queryForObject("SELECT current_user",String.class)))throw new IllegalArgumentException("Wrong DB identity");
            var json=JsonMapper.builder().build();var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
            var events=new TargetSearchEvents(jdbc,json);var targets=new TargetStore(jdbc,events);var messages=new PublicMessages(jdbc,json);
            long count=0,inserted=0;
            try(var reader=Files.newBufferedReader(input,StandardCharsets.UTF_8)) {
                String line;
                while((line=reader.readLine())!=null) {
                    if(++count>100000 || line.length()>65536)throw new IllegalArgumentException("Dataset row limit");
                    var row=json.readTree(line);String kind=row.path("type").asString(),value=row.path("value").asString(),label=switch(row.path("label").asString()) {case "0","benign","BENIGN" -> "BENIGN";case "1","phishing","SUSPICIOUS" -> "SUSPICIOUS";case "UNKNOWN" -> "UNKNOWN";default -> throw new IllegalArgumentException("Unknown dataset label");};
                    if(value.isBlank()||value.length()>10000||!Set.of("SUSPICIOUS","BENIGN","UNKNOWN").contains(label))throw new IllegalArgumentException("Invalid seed row");
                    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((kind+":"+value).getBytes(StandardCharsets.UTF_8)));
                    Boolean added=tx.execute(status->{
                        if(jdbc.queryForObject("SELECT count(*) FROM threat.dataset_import_rows WHERE dataset_name=? AND row_hash=?",Integer.class,args[0],hash)>0)return false;
                        UUID id;
                        if(kind.equals("TEXT")) id=messages.record(value,"DATASET",args[0],label,Instant.now());
                        else if(kind.equals("URL")) id=targets.resolve(TargetIdentity.of("URL",value));
                        else throw new IllegalArgumentException("Unsupported seed kind");
                        jdbc.update("INSERT INTO threat.dataset_import_rows(dataset_name,row_hash,target_id,dataset_label) VALUES(?,?,?,?)",args[0],hash,id,label);
                        if(kind.equals("URL"))events.publish("threat.updated",id,"URL",value,Instant.now(),UUID.randomUUID());
                        return true;
                    });
                    if(Boolean.TRUE.equals(added))inserted++;
                }
            }
            System.out.println("DATASET_SEARCH_IMPORT PASS rows="+count+" inserted="+inserted+" duplicate="+(count-inserted)+" rawPII=not_persisted reportStatus=unchanged");
        }catch(Exception ignored){System.err.println("DATASET_SEARCH_IMPORT FAIL input_role_or_transaction; raw rows/password withheld");System.exit(1);}
    }
}
