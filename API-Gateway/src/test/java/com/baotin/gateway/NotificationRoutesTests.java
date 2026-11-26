package com.baotin.gateway;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses the shipped endpoint list, not a test-only route override. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationRoutesTests {
    static final RSAKey KEY;
    static final HttpServer UPSTREAM;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("notification-route-test").generate();
            UPSTREAM=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            UPSTREAM.createContext("/jwks",e->{
                byte[] b=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);
                try(var out=e.getResponseBody()){out.write(b);}
            });
            UPSTREAM.createContext("/api/v1/",e->{
                boolean clean=e.getRequestHeaders().getFirst("X-User-Id")==null && e.getRequestHeaders().getFirst("X-Roles")==null;
                byte[] b=("{\"path\":\""+e.getRequestURI().getPath()+"\",\"method\":\""+e.getRequestMethod()+"\",\"clean\":"+clean+"}").getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);
                try(var out=e.getResponseBody()){out.write(b);}
            });UPSTREAM.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        String uri="http://127.0.0.1:"+UPSTREAM.getAddress().getPort();
        r.add("gateway.jwks-uri",()->uri+"/jwks");r.add("gateway.catalog.services.notification",()->uri);r.add("gateway.catalog.services.search",()->uri);
    }
    @LocalServerPort int port;
    @MockitoBean ReactiveStringRedisTemplate redis;
    @BeforeEach @SuppressWarnings({"unchecked","rawtypes"}) void limits(){when(redis.execute(any(RedisScript.class),anyList(),anyList())).thenReturn(Flux.just(1L));}
    @AfterAll static void close(){UPSTREAM.stop(0);}
    String token() throws Exception {
        Instant now=Instant.now();var c=new JWTClaimsSet.Builder().issuer("baotin-auth").audience("baotin-api").subject(UUID.randomUUID().toString())
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300))).jwtID(UUID.randomUUID().toString())
            .claim("sid",UUID.randomUUID().toString()).claim("ver",1).claim("token_use","access").claim("roles",List.of("USER")).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).keyID(KEY.getKeyID()).build(),c);
        jwt.sign(new RSASSASigner(KEY));return jwt.serialize();
    }
    HttpResponse<String> call(String method,String path,String access) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-User-Id","forged").header("X-Roles","ADMIN");
        if(access!=null)b.header("Authorization","Bearer "+access);
        return HttpClient.newHttpClient().send(b.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void everyReviewedNotificationAndWatchlistOperationReachesTheServiceWithJwt() throws Exception {
        String id="00000000-0000-4000-8000-000000000001";String access=token();
        String[][] cases={{"GET","/api/v1/notifications"},{"GET","/api/v1/notifications/unread-count"},{"POST","/api/v1/notifications/read-all"},
            {"PATCH","/api/v1/notifications/"+id+"/read"},{"DELETE","/api/v1/notifications/"+id},{"GET","/api/v1/notifications/preferences"},
            {"PATCH","/api/v1/notifications/preferences"},{"GET","/api/v1/notifications/devices"},{"POST","/api/v1/notifications/devices"},
            {"DELETE","/api/v1/notifications/devices/"+id},{"GET","/api/v1/watchlist"},{"POST","/api/v1/watchlist"},{"DELETE","/api/v1/watchlist/"+id}};
        for(String[] c:cases){var r=call(c[0],c[1],access);assertEquals(200,r.statusCode(),Arrays.toString(c)+r.body());
            assertTrue(r.body().contains("\"clean\":true"));assertTrue(r.body().contains("\"path\":\""+c[1]+"\""));}
    }
    @Test void guestSearchIsReadOnlyAndSpoofedHeadersAreRemoved() throws Exception {
        var response=call("GET","/api/v1/search?q=example",null);
        assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"clean\":true"));
        assertEquals(403,call("POST","/api/v1/search",token()).statusCode());
        assertEquals(403,call("GET","/api/v1/search/admin",token()).statusCode());
    }
    @Test void anonymousSpoofedAndUnlistedRequestsRemainDenied() throws Exception {
        for(String path:List.of("/api/v1/notifications","/api/v1/watchlist"))assertEquals(401,call("GET",path,null).statusCode());
        for(String path:List.of("/api/v1/watchlist/admin","/api/v1/notifications/devices/admin/extra","/internal/v1/models","/api/v1/search/admin","/api/v1/analytics"))
            assertEquals(403,call("GET",path,token()).statusCode(),path);
        assertEquals(403,call("PUT","/api/v1/watchlist",token()).statusCode());
    }
}
