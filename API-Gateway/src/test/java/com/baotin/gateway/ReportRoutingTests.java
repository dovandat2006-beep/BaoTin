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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/** Real edge HTTP/JWT/upstream tests; Redis fixture isolates the route contract from Docker. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportRoutingTests {
    static final RSAKey KEY;
    static final HttpServer UPSTREAM;
    static final AtomicInteger BYTES=new AtomicInteger();
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("report-route-test").generate();
            UPSTREAM=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            UPSTREAM.createContext("/jwks",exchange->{
                var bytes=new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
                try(var output=exchange.getResponseBody()) {output.write(bytes);}
            });
            UPSTREAM.createContext("/api/v1/",exchange->{
                BYTES.set(exchange.getRequestBody().readAllBytes().length);byte[] body="{\"fixture\":true}".getBytes();
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) {output.write(body);}
            });UPSTREAM.start();
        }catch(Exception e) {throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String uri="http://127.0.0.1:"+UPSTREAM.getAddress().getPort();
        r.add("gateway.jwks-uri",()->uri+"/jwks");r.add("gateway.catalog.services.report",()->uri);
        r.add("gateway.catalog.services.analytics",()->uri);
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
    @Test void reportsRequireJwtAndModerationRequiresServerRole() throws Exception {
        assertEquals(401,send(request("/api/v1/reports/me",null).GET().build()));
        assertEquals(200,send(request("/api/v1/reports/me","USER").GET().build()));
        assertEquals(403,send(request("/api/v1/moderation/reports","USER").header("X-Roles","ADMIN").GET().build()));
        assertEquals(200,send(request("/api/v1/moderation/reports","MODERATOR").GET().build()));
    }
    @Test void analyticsAdminRoutesRequireServerRole() throws Exception {
        assertEquals(401,send(request("/api/v1/analytics/admin/summary",null).GET().build()));
        assertEquals(403,send(request("/api/v1/analytics/admin/summary","USER").header("X-Roles","ADMIN").GET().build()));
        assertEquals(200,send(request("/api/v1/analytics/admin/summary","MODERATOR").GET().build()));
        assertEquals(200,send(request("/api/v1/analytics/admin/queue","ADMIN").GET().build()));
        assertEquals(200,send(request("/api/v1/analytics/admin/counts","MODERATOR").GET().build()));
        assertEquals(403,send(request("/api/v1/analytics/admin/unknown","ADMIN").GET().build()));
    }
    @Test void corsAllowsIdempotencyKeyForCreation() throws Exception {
        var request=request("/api/v1/reports",null).header("Origin","https://localhost:9444")
            .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Authorization,Idempotency-Key,Content-Type")
            .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(200,send(request));
    }
    @Test void multipartGetsLargerBoundWithoutRelaxingJsonOrOtherRoutes() throws Exception {
        byte[] data=new byte[70*1024];String id=UUID.randomUUID().toString();String evidence="/api/v1/reports/"+id+"/evidence";
        assertEquals(200,send(request(evidence,"USER").header("Content-Type","multipart/form-data; boundary=test").POST(HttpRequest.BodyPublishers.ofByteArray(data)).build()));
        assertEquals(data.length,BYTES.get());
        assertEquals(413,send(request("/api/v1/reports","USER").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(data)).build()));
        byte[] below=new byte[11*1024*1024-1];
        assertEquals(200,send(request(evidence,"USER").header("Content-Type","multipart/form-data; boundary=test").POST(HttpRequest.BodyPublishers.ofByteArray(below)).build()));
        assertEquals(below.length,BYTES.get());
        byte[] exact=new byte[11*1024*1024];
        assertEquals(200,send(request(evidence,"USER").header("Content-Type","multipart/form-data; boundary=test").POST(HttpRequest.BodyPublishers.ofByteArray(exact)).build()));
        assertEquals(exact.length,BYTES.get());
        assertEquals(413,send(request(evidence,"USER").header("Content-Type","multipart/form-data; boundary=test").POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(new byte[11*1024*1024+1]))).build()));
    }
    @Test void internalAndUnknownActionsRemainClosed() throws Exception {
        assertEquals(403,send(request("/internal/v1/targets/resolve","ADMIN").POST(HttpRequest.BodyPublishers.ofString("{}")).build()));
        assertEquals(403,send(request("/api/v1/moderation/reports/"+UUID.randomUUID()+"/arbitrary","ADMIN").POST(HttpRequest.BodyPublishers.ofString("{}")).build()));
        assertEquals(403,send(request("/api/v1/reports/"+UUID.randomUUID()+"/arbitrary","USER").POST(HttpRequest.BodyPublishers.ofString("{}")).build()));
    }
}
