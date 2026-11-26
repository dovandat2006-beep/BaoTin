package com.baotin.report.infrastructure;

import com.baotin.report.api.ReportFailure;
import java.util.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Component
public class TargetClient {
    public record Target(UUID id, String type, String displayValue) {}
    private final RestClient client;
    public TargetClient(@Value("${report.threat-uri}") String uri,@Value("${report.internal-token}") String token) {
        var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client=RestClient.builder().baseUrl(uri).requestFactory(factory).defaultHeader("X-Service-Token",token).build();
    }
    public Target resolve(String type,String value) {
        return resolve(type,value,null,null);
    }
    public Target resolve(String type,String value,UUID scanId,UUID reporter) {
        try {
            var body=new LinkedHashMap<String,Object>();body.put("type",type);body.put("value",value);
            if(scanId!=null) { body.put("scanId",scanId);body.put("reporterUserId",reporter); }
            var result=client.post().uri("/internal/v1/targets/resolve").body(body).retrieve().body(Target.class);
            if(result==null || result.id()==null || !type.equals(result.type()) || result.displayValue()==null) throw new IllegalStateException();
            return result;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            throw new ReportFailure(404,"SOURCE_SCAN_NOT_FOUND","Source scan was not found for this user.");
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            throw new ReportFailure(400,"INVALID_TARGET","URL hoặc nội dung tin nhắn không hợp lệ.");
        } catch(Exception e) { throw new ReportFailure(503,"TARGET_UNAVAILABLE","Chưa kết nối được dịch vụ đối tượng. Hãy thử lại."); }
    }
}
