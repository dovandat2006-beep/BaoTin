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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/** Real signed JWT / HTTP edge tests. A permissive upstream proves denials occur at edge. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminBoundaryTests {
    static final RSAKey KEY;
    static final HttpServer UPSTREAM;
    static final AtomicInteger HITS=new AtomicInteger();
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("admin-boundary-test").generate();
            UPSTREAM=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            UPSTREAM.createContext("/jwks",exchange->{
                byte[] body=new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()){output.write(body);}
            });
            UPSTREAM.createContext("/api/v1/auth/",exchange->{
                HITS.incrementAndGet();exchange.getRequestBody().readAllBytes();
                byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()){output.write(body);}
            });UPSTREAM.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        String uri="http://127.0.0.1:"+UPSTREAM.getAddress().getPort();
        r.add("gateway.jwks-uri",()->uri+"/jwks");r.add("gateway.auth-uri",()->uri);
    }
    @LocalServerPort int port;
    @MockitoBean ReactiveStringRedisTemplate redis;
    @BeforeEach @SuppressWarnings({"unchecked","rawtypes"}) void redisAvailable(){
        when(redis.execute(any(RedisScript.class),anyList(),anyList())).thenReturn(Flux.just(1L));HITS.set(0);
    }
    @AfterAll static void stop(){UPSTREAM.stop(0);}
    String jwt(String role) throws Exception {
        var now=Instant.now();var claims=new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer("baotin-auth").audience("baotin-api")
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300))).jwtID(UUID.randomUUID().toString())
            .claim("sid",UUID.randomUUID().toString()).claim("ver",1).claim("token_use","access").claim("roles",List.of(role)).build();
        var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(new JOSEObjectType("at+jwt")).build(),claims);
        token.sign(new RSASSASigner(KEY));return token.serialize();
    }
    HttpResponse<String> send(String method,String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .header("X-Roles","ADMIN").header("X-User-Role","ADMIN").header("X-User-Id",UUID.randomUUID().toString());
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void guestUserAndModeratorNeverReachAdminUpstream() throws Exception {
        for(String role:Arrays.asList(null,"USER","MODERATOR")){
            String token=role==null?null:jwt(role);int expected=role==null?401:403;
            assertEquals(expected,send("GET","/api/v1/auth/admin/users",token).statusCode());
            assertEquals(expected,send("PATCH","/api/v1/auth/admin/users/"+UUID.randomUUID()+"/role",token).statusCode());
            assertEquals(expected,send("PATCH","/api/v1/auth/admin/users/"+UUID.randomUUID()+"/status",token).statusCode());
        }
        assertEquals(0,HITS.get());
    }
    @Test void signedAdminReachesServiceForItsIndependentSessionAndStepUpChecks() throws Exception {
        String token=jwt("ADMIN");
        assertEquals(200,send("GET","/api/v1/auth/admin/users",token).statusCode());
        assertEquals(200,send("PATCH","/api/v1/auth/admin/users/"+UUID.randomUUID()+"/role",token).statusCode());
        assertEquals(200,send("PATCH","/api/v1/auth/admin/users/"+UUID.randomUUID()+"/status",token).statusCode());
        assertEquals(3,HITS.get());
    }
    @Test void tamperedRoleAndUnknownAdminOperationsAreRejected() throws Exception {
        String token=jwt("USER");String[] parts=token.split("\\.");
        String payload=new String(Base64.getUrlDecoder().decode(parts[1]),java.nio.charset.StandardCharsets.UTF_8).replace("USER","ADMIN");
        String tampered=parts[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+parts[2];
        var denied=send("GET","/api/v1/auth/admin/users",tampered);
        assertEquals(401,denied.statusCode());assertTrue(denied.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertEquals(403,send("POST","/api/v1/auth/admin/grant",jwt("ADMIN")).statusCode());
        assertEquals(0,HITS.get());
    }
}
