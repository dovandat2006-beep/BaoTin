package com.baotin.notification.infrastructure;

import com.baotin.notification.api.ApiFailure;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls only the configured private Threat origin; never follows the watched URL. */
@Component
public class ThreatTargetClient {
    private record Target(UUID id,String type,String displayValue) {}
    private final RestClient client;
    public ThreatTargetClient(@Value("${notification.threat-uri:}") String origin,
                              @Value("${notification.threat-token:}") String token) {
        if(origin.isBlank() && token.isBlank()){client=null;return;}
        URI uri=URI.create(origin);
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null ||
           uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("","/").contains(uri.getPath()) || token.length()<32)
            throw new IllegalArgumentException("Private Threat origin and service credential are required together");
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(Duration.ofSeconds(2));
        client=RestClient.builder().baseUrl(uri.toString()).requestFactory(factory).defaultHeader("X-Service-Token",token).build();
    }
    /** Null preserves legacy standalone deployments; integrated deployments configure both fields. */
    public UUID resolve(String url) {
        if(client==null)return null;
        try {
            Target target=client.post().uri("/internal/v1/targets/resolve").body(Map.of("type","URL","value",url)).retrieve().body(Target.class);
            if(target==null || target.id()==null || !"URL".equals(target.type()))throw new IllegalStateException();
            return target.id();
        }catch(Exception e){throw new ApiFailure(503,"TARGET_UNAVAILABLE","Chưa kết nối được dịch vụ đối tượng. Hãy thử lại.");}
    }
}
