package com.baotin.gateway;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/** Isolated context: JWKS is a closed port while a live auth upstream counts any forwarded request. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwksUnavailableTests {
    static final RSAKey KEY;
    static final HttpServer UPSTREAM;
    static final AtomicInteger FORWARDED=new AtomicInteger();
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("unreachable-jwks").generate();
            UPSTREAM=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            UPSTREAM.createContext("/api/v1/",exchange->{
                FORWARDED.incrementAndGet();byte[] body="{\"forwarded\":true}".getBytes();
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()){output.write(body);}
            });UPSTREAM.start();
        }catch(Exception e) {throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("gateway.jwks-uri",()->"http://127.0.0.1:1/.well-known/jwks.json");
        r.add("gateway.auth-uri",()->"http://127.0.0.1:"+UPSTREAM.getAddress().getPort());
    }
    @LocalServerPort int port;
    @MockitoBean ReactiveStringRedisTemplate redis;
    @BeforeEach @SuppressWarnings({"unchecked","rawtypes"}) void redisAvailable() {
        when(redis.execute(any(RedisScript.class),anyList(),anyList())).thenReturn(Flux.just(1L));
    }
    @AfterAll static void stop() {UPSTREAM.stop(0);}
    @Test void unreachableJwksFailsClosedWithoutForwarding() throws Exception {
        var now=Instant.now();
        var claims=new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer("baotin-auth").audience("baotin-api")
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString()).claim("sid",UUID.randomUUID().toString()).claim("ver",1)
            .claim("token_use","access").claim("roles",List.of("USER")).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(new JOSEObjectType("at+jwt")).build(),claims);
        jwt.sign(new RSASSASigner(KEY));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/me"))
            .header("Authorization","Bearer "+jwt.serialize()).GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(0,FORWARDED.get(),"JWKS failure must not forward the request");
        assertTrue(response.statusCode()==401 || response.statusCode()==503,response.body());
        assertTrue(response.body().contains("\"code\":\"UNAUTHORIZED\"") || response.body().contains("\"code\":\"UPSTREAM_UNAVAILABLE\""),response.body());
        assertFalse(response.body().contains("\"forwarded\":true"),response.body());
    }
}
