package com.baotin.report.infrastructure;

import io.minio.*;
import java.io.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.baotin.report.api.ReportFailure;

@Component
public class EvidenceStorage {
    private final MinioClient client;
    private final String bucket;
    public EvidenceStorage(@Value("${report.minio.endpoint}") String endpoint,@Value("${report.minio.access-key}") String access,
        @Value("${report.minio.secret-key}") String secret,@Value("${report.minio.bucket}") String bucket) {
        client=MinioClient.builder().endpoint(endpoint).credentials(access,secret).build();this.bucket=bucket;
    }
    public void put(String key,byte[] data,String mime) {
        try { client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(mime).stream(new ByteArrayInputStream(data),data.length,-1).build()); }
        catch(Exception e) { throw unavailable(); }
    }
    public void remove(String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch(Exception e) { throw unavailable(); }
    }
    public InputStream open(String key) {
        try { return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch(Exception e) { throw unavailable(); }
    }
    private static ReportFailure unavailable() { return new ReportFailure(503,"EVIDENCE_STORAGE_UNAVAILABLE","Kho bằng chứng chưa sẵn sàng. Hãy thử lại."); }
}
