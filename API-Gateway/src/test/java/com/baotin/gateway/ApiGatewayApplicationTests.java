package com.baotin.gateway;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final HttpServer UPSTREAM;
    static HttpServer THREAT;
    static int threatPort;
    static final RSAKey KEY;
    static final RSAKey PREVIOUS;
    static {
        try {
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
            KEY=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).keyID("test-key").build();
            var previous=generator.generateKeyPair();PREVIOUS=new RSAKey.Builder((RSAPublicKey)previous.getPublic()).privateKey(previous.getPrivate()).keyID("previous-key").build();
            UPSTREAM=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            UPSTREAM.createContext("/.well-known/jwks.json",e->{
                byte[] body=new JWKSet(List.of(KEY.toPublicJWK(),PREVIOUS.toPublicJWK())).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });
            UPSTREAM.createContext("/api/v1/auth/",e->{
                String origin=e.getRequestHeaders().getFirst("Origin");
                if(origin!=null){
                    e.getResponseHeaders().set("Access-Control-Allow-Origin",origin);
                    e.getResponseHeaders().set("Access-Control-Allow-Credentials","true");
                    e.getResponseHeaders().add("Set-Cookie","__Host-baotin-refresh=synthetic; Path=/; Secure; HttpOnly; SameSite=Lax");
                }
                boolean clean=e.getRequestHeaders().getFirst("X-User-Id")==null && e.getRequestHeaders().getFirst("X-Roles")==null && e.getRequestHeaders().getFirst("Tailscale-User-Login")==null;
                String ip=e.getRequestHeaders().getFirst("X-BaoTin-Client-IP");
                byte[] body=("{\"forwarded\":true,\"clean\":"+clean+",\"clientIp\":\""+ip+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });UPSTREAM.start();
            THREAT=businessServer(0);threatPort=THREAT.getAddress().getPort();THREAT.start();REDIS.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static HttpServer businessServer(int port)throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/api/v1/",e->{
            boolean clean=e.getRequestHeaders().getFirst("X-Roles")==null && e.getRequestHeaders().getFirst("CF-Connecting-IP")==null;
            byte[] body=("{\"fixture\":true,\"clean\":"+clean+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });return server;
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){
        r.add("gateway.auth-uri",()->"http://127.0.0.1:"+UPSTREAM.getAddress().getPort());
        r.add("gateway.jwks-uri",()->"http://127.0.0.1:"+UPSTREAM.getAddress().getPort()+"/.well-known/jwks.json");
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("gateway.trusted-proxy-cidrs",()->"127.0.0.1/32");
        r.add("gateway.allowed-origins",()->"https://localhost:9444,https://web.baotin.test");
        r.add("gateway.catalog.services.threat",()->"http://127.0.0.1:"+threatPort);
        r.add("gateway.catalog.services.notification",()->"http://127.0.0.1:"+threatPort);
        String[] ids={"list-scans","create-scan","lookup","history","list-notifications","unread-notifications",
            "read-all-notifications","read-notification","delete-notification","get-notification-preferences",
            "update-notification-preferences","list-devices","register-device","delete-device",
            "list-watchlist","add-watchlist","delete-watchlist"};
        String[] paths={"/api/v1/scans","/api/v1/scans","/api/v1/threats/{id}","/api/v1/threats/history",
            "/api/v1/notifications","/api/v1/notifications/unread-count","/api/v1/notifications/read-all",
            "/api/v1/notifications/{id}/read","/api/v1/notifications/{id}","/api/v1/notifications/preferences",
            "/api/v1/notifications/preferences","/api/v1/notifications/devices","/api/v1/notifications/devices",
            "/api/v1/notifications/devices/{id}","/api/v1/watchlist","/api/v1/watchlist","/api/v1/watchlist/{id}"};
        String[] services={"threat","threat","threat","threat","notification","notification","notification",
            "notification","notification","notification","notification","notification","notification",
            "notification","notification","notification","notification"};
        String[] methods={"GET","POST","GET","GET","GET","GET","POST","PATCH","DELETE","GET","PATCH","GET",
            "POST","DELETE","GET","POST","DELETE"};
        boolean[] anonymous={false,false,true,false,false,false,false,false,false,false,false,false,false,false,false,false,false};
        for(int i=0;i<ids.length;i++){
            final int n=i;String base="gateway.catalog.endpoints["+i+"]";
            r.add(base+".id",()->ids[n]);r.add(base+".service",()->services[n]);r.add(base+".path",()->paths[n]);
            r.add(base+".methods[0]",()->methods[n]);r.add(base+".anonymous",()->anonymous[n]);
        }
    }
    @LocalServerPort int port;
    private static final JOSEObjectType ACCESS_TOKEN_TYPE=new JOSEObjectType("at+jwt");
    private JWTClaimsSet.Builder baseClaims(){
        Instant now=Instant.now();
        return new JWTClaimsSet.Builder().issuer("baotin-auth").audience("baotin-api").subject(UUID.randomUUID().toString()).jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)))
            .claim("sid",UUID.randomUUID().toString()).claim("ver",0).claim("token_use","access").claim("roles",List.of("USER"));
    }
    private String token(String issuer,String audience,String use,Instant expires)throws Exception{
        return tokenWithKey(KEY,issuer,audience,use,expires);
    }
    private String tokenWithKey(RSAKey key,String issuer,String audience,String use,Instant expires)throws Exception{
        return signed(key,ACCESS_TOKEN_TYPE,baseClaims().issuer(issuer).audience(audience).expirationTime(Date.from(expires)).claim("token_use",use).build());
    }
    private String signed(RSAKey key,JOSEObjectType type,JWTClaimsSet claims)throws Exception{
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(type).build(),claims);
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));return jwt.serialize();
    }
    private HttpResponse<String> get(String path,String token)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> request(String method,String path,String token)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null)request.header("Authorization","Bearer "+token);
        request.method(method,HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void allowsSignedAccessAndRejectsInvalidIssuerAudienceTypeAndExpiry()throws Exception{
        var future=Instant.now().plusSeconds(600);
        assertEquals(200,get("/api/v1/auth/me",token("baotin-auth","baotin-api","access",future)).statusCode());
        assertEquals(401,get("/api/v1/auth/me",null).statusCode());
        assertEquals(401,get("/api/v1/auth/me","malformed").statusCode());
        assertEquals(401,get("/api/v1/auth/me",token("other","baotin-api","access",future)).statusCode());
        assertEquals(401,get("/api/v1/auth/me",token("baotin-auth","other","access",future)).statusCode());
        assertEquals(401,get("/api/v1/auth/me",token("baotin-auth","baotin-api","challenge",future)).statusCode());
        assertEquals(401,get("/api/v1/auth/me",token("baotin-auth","baotin-api","access",Instant.now().minusSeconds(60))).statusCode());
    }
    @Test void rejectsMissingRequiredJwtClaimsWrongTypeAndOverlongLifetime()throws Exception{
        JWTClaimsSet complete=baseClaims().build();
        for(String claim:List.of("sub","jti","sid","ver","iat","nbf","exp")){
            Map<String,Object> json=new LinkedHashMap<>(complete.toJSONObject());json.remove(claim);
            assertEquals(401,get("/api/v1/auth/me",signed(KEY,ACCESS_TOKEN_TYPE,JWTClaimsSet.parse(json))).statusCode(),claim);
        }
        assertEquals(401,get("/api/v1/auth/me",signed(KEY,new JOSEObjectType("JWT"),complete)).statusCode());
        Instant now=Instant.now();
        assertEquals(401,get("/api/v1/auth/me",signed(KEY,ACCESS_TOKEN_TYPE,baseClaims().expirationTime(Date.from(now.plusSeconds(660))).build())).statusCode());
        assertEquals(401,get("/api/v1/auth/me",signed(KEY,ACCESS_TOKEN_TYPE,baseClaims().issueTime(Date.from(now.plusSeconds(60))).expirationTime(Date.from(now.plusSeconds(120))).build())).statusCode());
    }
    @Test void knownServiceNeedsManualEndpointAndJwtBeforeForwarding()throws Exception{
        String access=token("baotin-auth","baotin-api","access",Instant.now().plusSeconds(600));
        assertEquals(401,get("/api/v1/scans",null).statusCode());
        assertEquals(200,get("/api/v1/scans",access).statusCode());
        assertEquals(403,get("/api/v1/scans/unlisted",access).statusCode());
        assertEquals(403,get("/api/v1/reports",access).statusCode());
        assertEquals(200,get("/api/v1/threats/abc",null).statusCode());
        assertEquals(401,get("/api/v1/threats/history",null).statusCode());
        assertEquals(403,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/scans"))
            .header("Authorization","Bearer "+access).DELETE().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400,get("/api/v1/%73cans",access).statusCode());
    }
    @Test void analyticsNearMatchPathsStayDenied()throws Exception{
        // Only the three exact reviewed admin paths are routed; near matches remain closed.
        String access=token("baotin-auth","baotin-api","access",Instant.now().plusSeconds(600));
        for(String path:List.of("/api/v1/analytics","/api/v1/analytics/summary","/api/v1/scans-extra","/api/v1/threats")){
            var response=get(path,access);assertEquals(403,response.statusCode(),path);assertTrue(response.body().contains("\"code\":\"FORBIDDEN\""),path);
        }
        var wrongMethod=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/threats/abc")).header("Authorization","Bearer "+access)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403,HttpClient.newHttpClient().send(wrongMethod,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void notificationAndWatchlistRoutesStayBehindJwtAndExactCatalogPaths()throws Exception{
        String access=token("baotin-auth","baotin-api","access",Instant.now().plusSeconds(600));
        assertEquals(401,get("/api/v1/notifications",null).statusCode());
        assertEquals(401,get("/api/v1/watchlist",null).statusCode());
        assertEquals(200,get("/api/v1/notifications",access).statusCode());
        assertEquals(200,get("/api/v1/notifications/unread-count",access).statusCode());
        assertEquals(200,get("/api/v1/notifications/preferences",access).statusCode());
        assertEquals(200,get("/api/v1/notifications/devices",access).statusCode());
        assertEquals(200,get("/api/v1/watchlist",access).statusCode());
        assertEquals(200,request("POST","/api/v1/notifications/read-all",access).statusCode());
        assertEquals(200,request("POST","/api/v1/watchlist",access).statusCode());
        assertEquals(200,request("PATCH","/api/v1/notifications/preferences",access).statusCode());
        assertEquals(200,request("PATCH","/api/v1/notifications/00000000-0000-0000-0000-000000000001/read",access).statusCode());
        assertEquals(200,request("DELETE","/api/v1/notifications/00000000-0000-0000-0000-000000000001",access).statusCode());
        assertEquals(200,request("DELETE","/api/v1/notifications/devices/00000000-0000-0000-0000-000000000001",access).statusCode());
        assertEquals(200,request("DELETE","/api/v1/watchlist/00000000-0000-0000-0000-000000000001",access).statusCode());
        assertEquals(403,get("/api/v1/notifications/admin",access).statusCode());
        assertEquals(403,get("/api/v1/watchlist/admin",access).statusCode());
    }

    @Test void deployedServiceReconnectsAtSameAddressWithoutGatewayRestart()throws Exception{
        String access=token("baotin-auth","baotin-api","access",Instant.now().plusSeconds(600));
        assertEquals(200,get("/api/v1/scans",access).statusCode());
        THREAT.stop(0);
        try{
            var down=get("/api/v1/scans",access);assertEquals(503,down.statusCode());
            assertTrue(down.body().contains("UPSTREAM_UNAVAILABLE"));assertFalse(down.body().contains("127.0.0.1:"+threatPort));
        }finally{THREAT=businessServer(threatPort);THREAT.start();}
        assertEquals(200,get("/api/v1/scans",access).statusCode());
    }
    @Test void internetCorsAndCloudflareSpoofingKeepManualRouteBoundaries()throws Exception{
        String access=token("baotin-auth","baotin-api","access",Instant.now().plusSeconds(600));
        var client=HttpClient.newHttpClient();
        var preflight=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/scans")).header("Origin","https://web.baotin.test")
            .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,content-type").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        var cors=client.send(preflight,HttpResponse.BodyHandlers.ofString());assertEquals(200,cors.statusCode());
        assertEquals("https://web.baotin.test",cors.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("true",cors.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/scans")).header("Origin","https://web.baotin.test")
            .header("Authorization","Bearer "+access).header("CF-Connecting-IP","1.2.3.4").header("X-Roles","ADMIN").GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"clean\":true"));
        var untrusted=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/scans")).header("Origin","https://web.baotin.test.attacker.test")
            .header("Authorization","Bearer "+access).GET().build();assertEquals(403,client.send(untrusted,HttpResponse.BodyHandlers.ofString()).statusCode());
        var bytes="x".repeat(65537).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var large=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/scans")).header("Authorization","Bearer "+access).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(bytes))).build();
        assertEquals(413,client.send(large,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void deniesInternalManagementAndWrongMethod()throws Exception{
        String access=token("baotin-auth","baotin-api","access",Instant.now().plusSeconds(600));
        assertEquals(403,get("/internal/v1/inference/sms",access).statusCode());
        assertEquals(403,get("/actuator/env",access).statusCode());
        assertEquals(403,get("/api/v1/auth/login",access).statusCode());
        assertEquals(200,get("/actuator/health",null).statusCode());
    }
    @Test void stripsClientIdentityHeadersAndBoundsBody()throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login"))
            .header("X-User-Id","admin").header("X-Roles","ADMIN").header("Tailscale-User-Login","admin@example.test").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"clean\":true"));assertTrue(response.headers().firstValue("X-Correlation-Id").isPresent());
        var large=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/register")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("a".repeat(65537))).build();
        assertEquals(413,HttpClient.newHttpClient().send(large,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void acceptsBodyAtExact64KiBLimit()throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(65536))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"forwarded\":true"));
        var below=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(65535))).build();
        var accepted=HttpClient.newHttpClient().send(below,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,accepted.statusCode());assertTrue(accepted.body().contains("\"forwarded\":true"));
    }
    @Test void signatureTamperingAndCorsAreRejected()throws Exception{
        String value=token("baotin-auth","baotin-api","access",Instant.now().plusSeconds(600));
        String[] parts=value.split("\\.");String signature=parts[2];parts[2]=(signature.charAt(0)=='A'?"B":"A")+signature.substring(1);
        assertEquals(401,get("/api/v1/auth/me",String.join(".",parts)).statusCode());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login"))
            .header("Origin","https://untrusted.test").header("Access-Control-Request-Method","POST").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(403,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void browserCorsHeadersStaySingleWhenAuthAlsoEmitsCors()throws Exception{
        String origin="https://localhost:9444";
        for(String path:List.of("/api/v1/auth/web/login","/api/v1/auth/oauth2/callback/google")){
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin",origin);
            if(path.endsWith("login"))builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));else builder.GET();
            var response=HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            assertEquals(List.of(origin),response.headers().allValues("Access-Control-Allow-Origin"));
            assertEquals(List.of("true"),response.headers().allValues("Access-Control-Allow-Credentials"));
            assertEquals(1,response.headers().allValues("Set-Cookie").size());
        }
    }
    @Test void redisOutageFailsClosedForLogin()throws Exception{
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try{
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(503,response.statusCode());assertTrue(response.body().contains("RATE_LIMIT_UNAVAILABLE"));
        }finally{REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();}
    }
    @Test void acceptsPreviousPublicKeyDuringRotationOverlap()throws Exception{
        assertEquals(200,get("/api/v1/auth/me",tokenWithKey(PREVIOUS,"baotin-auth","baotin-api","access",Instant.now().plusSeconds(600))).statusCode());
        var unknown=new RSAKey.Builder(KEY.toRSAPublicKey()).privateKey(KEY.toRSAPrivateKey()).keyID("unpublished-key").build();
        assertEquals(401,get("/api/v1/auth/me",tokenWithKey(unknown,"baotin-auth","baotin-api","access",Instant.now().plusSeconds(600))).statusCode());
    }
    @Test void trustedProxyIdentityIsCanonicalAndClientCannotOverrideInternalHeader()throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login"))
            .header("X-Forwarded-For","203.0.113.90,198.51.100.71").header("X-BaoTin-Client-IP","1.2.3.4").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        var result=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,result.statusCode());assertTrue(result.body().contains("\"clientIp\":\"198.51.100.71\""));assertFalse(result.body().contains("1.2.3.4"));
    }
    @Test void rejectsMalformedForwardedClientAddress()throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("X-Forwarded-For","evil.test")
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(400,response.statusCode());assertTrue(response.body().contains("\"code\":\"FORWARDED_IP_INVALID\""));
    }
    @Test void exactIpv6HostProxyContinuesChainWithoutTrustingItsNetwork(){
        String peer="172.29.47.12",client="100.64.1.5",host="fd00:abcd:0:0:0:0:0:6";
        String chain="203.0.113.77,"+client+",fd00:abcd::6";
        assertEquals(host,new ProxyAddresses("172.29.47.12/32,172.29.47.1/32").client(peer,chain));
        var configured=new ProxyAddresses("172.29.47.12/32,172.29.47.1/32,fd00:abcd::6/128");
        assertEquals(client,configured.client(peer,chain));
        assertEquals("fd00:abcd:0:0:0:0:0:7",configured.client(peer,"203.0.113.77,"+client+",fd00:abcd::7"));
        assertFalse(configured.trusted(client));
    }
    @Test void chunkedOversizeAndCrossRouteRateLimitAreEnforced()throws Exception{
        var bytes="x".repeat(65537).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var large=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("X-Forwarded-For","198.51.100.80").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(bytes))).build();
        assertEquals(413,HttpClient.newHttpClient().send(large,HttpResponse.BodyHandlers.ofString()).statusCode());
        var client=HttpClient.newHttpClient();int limited=0;
        for(int i=0;i<31;i++){
            String path=i%2==0?"/api/v1/auth/login":"/api/v1/auth/refresh";
            var call=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Forwarded-For","198.51.100.81").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var response=client.send(call,HttpResponse.BodyHandlers.ofString());if(response.statusCode()==429){limited++;assertTrue(response.headers().firstValue("Retry-After").isPresent());}
        }
        assertTrue(limited>=1);
    }
    @Test void verifiedPrincipalLimitCannotBeResetByChangingClientAddress()throws Exception{
        String access=token("baotin-auth","baotin-api","access",Instant.now().plusSeconds(600));var client=HttpClient.newHttpClient();
        int limited=0;
        for(int i=0;i<101;i++){
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/me")).header("Authorization","Bearer "+access).header("X-Forwarded-For","198.51.100."+(100+i)).GET().build();
            var result=client.send(request,HttpResponse.BodyHandlers.ofString());if(result.statusCode()==429){limited++;assertTrue(result.body().contains("PRINCIPAL_RATE_LIMITED"));}
        }
        assertTrue(limited>=1);
    }
    @Test void emailMfaResendIsAnonymousButEnrollmentAndActionsRequireJwt()throws Exception{
        for(String path:List.of("/api/v1/auth/mfa/email/challenges","/api/v1/auth/web/mfa/email/challenges")){
            var q=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header("Origin","https://web.baotin.test").POST(HttpRequest.BodyPublishers.ofString("{\"challenge\":\"opaque-fixture\"}"));
            assertEquals(200,HttpClient.newHttpClient().send(q.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        for(String path:List.of("/api/v1/auth/mfa/email/enrollments","/api/v1/auth/mfa/email/enrollments/confirmation","/api/v1/auth/mfa/email/action-challenges","/api/v1/auth/mfa/email/step-up")){
            var q=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
            assertEquals(401,HttpClient.newHttpClient().send(q.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

}
