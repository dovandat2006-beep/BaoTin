package com.baotin.gateway;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/** Real edge HTTP/JWT tests with an explicitly opted-in synthetic Analytics upstream. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsRoutingTests {
    static final RSAKey KEY;
    static final HttpServer UPSTREAM;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("analytics-route-test").generate();
            UPSTREAM=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            UPSTREAM.createContext("/jwks",exchange->{
                var bytes=new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
                try(var output=exchange.getResponseBody()) {output.write(bytes);}
            });
            UPSTREAM.createContext("/api/v1/",exchange->{
                String query=exchange.getRequestURI().getRawQuery();
                byte[] body=("{\"fixture\":true,\"query\":\""+(query==null?"":query)+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) {output.write(body);}
            });
            UPSTREAM.start();
        }catch(Exception e) {throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String uri="http://127.0.0.1:"+UPSTREAM.getAddress().getPort();
        r.add("gateway.jwks-uri",()->uri+"/jwks");r.add("gateway.catalog.services.analytics",()->uri);
        var endpoints=AnalyticsEndpoints.all();
        for(int i=0;i<endpoints.size();i++) {
            var endpoint=endpoints.get(i);String prefix="gateway.catalog.endpoints["+i+"]";
            r.add(prefix+".id",endpoint::id);r.add(prefix+".service",endpoint::service);
            r.add(prefix+".path",endpoint::path);r.add(prefix+".methods[0]",()->"GET");
            r.add(prefix+".anonymous",()->false);
        }
    }
    @LocalServerPort int port;
    @MockitoBean ReactiveStringRedisTemplate redis;
    @BeforeEach @SuppressWarnings({"unchecked","rawtypes"}) void redisAvailable() {
        when(redis.execute(any(RedisScript.class),anyList(),anyList())).thenReturn(Flux.just(1L));
    }
    @AfterAll static void stop() {UPSTREAM.stop(0);}
    String jwt(String role) throws Exception {
        var now=Instant.now();var claims=new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer("baotin-auth").audience("baotin-api")
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300))).jwtID(UUID.randomUUID().toString())
            .claim("sid",UUID.randomUUID().toString()).claim("ver",1).claim("token_use","access").claim("roles",List.of(role)).build();
        var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(new JOSEObjectType("at+jwt")).build(),claims);
        token.sign(new RSASSASigner(KEY));return token.serialize();
    }
    HttpRequest.Builder request(String path,String role) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(role!=null)builder.header("Authorization","Bearer "+jwt(role));return builder;
    }
    int send(HttpRequest request) throws Exception {return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode();}
    @Test void analyticsRequiresJwtAndAdminOrModeratorRole() throws Exception {
        assertEquals(401,send(request("/api/v1/analytics/admin/summary",null).GET().build()));
        assertEquals(403,send(request("/api/v1/analytics/admin/summary","USER").GET().build()));
        assertEquals(200,send(request("/api/v1/analytics/admin/summary","ADMIN").GET().build()));
        assertEquals(200,send(request("/api/v1/analytics/admin/queue","MODERATOR").GET().build()));
        assertEquals(200,send(request("/api/v1/analytics/admin/counts","ADMIN").GET().build()));
        assertEquals(401,send(request("/api/v1/analytics/admin/trends?days=7",null).GET().build()));
        assertEquals(403,send(request("/api/v1/analytics/admin/trends?days=7","USER").GET().build()));
        var trendsResponse=HttpClient.newHttpClient().send(request("/api/v1/analytics/admin/trends?days=7","MODERATOR").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,trendsResponse.statusCode());
        assertTrue(trendsResponse.body().contains("\"query\":\"days=7\""));
    }
    @Test void analyticsNearMatchesAndWrongMethodsStayClosed() throws Exception {
        for (String path : List.of("/api/v1/analytics", "/api/v1/analytics/admin",
                "/api/v1/analytics/admin/summary/extra", "/api/v1/analytics/admin/trends/extra", "/api/v1/analytics/admin/export")) {
            assertEquals(403,send(request(path,"ADMIN").GET().build()),path);
        }
        assertEquals(403,send(request("/api/v1/analytics/admin/summary","ADMIN").POST(HttpRequest.BodyPublishers.ofString("{}")).build()));
        assertEquals(403,send(request("/api/v1/analytics/admin/trends","ADMIN").POST(HttpRequest.BodyPublishers.ofString("{}")).build()));
    }
}
