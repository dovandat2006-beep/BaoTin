package com.baotin.report;

import com.baotin.report.infrastructure.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ReportSecurityTests {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("report-test").generate();
            JWKS=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            JWKS.createContext("/jwks",exchange->{
                byte[] body=new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                try(var out=exchange.getResponseBody()) {out.write(body);}
            });JWKS.start();
        } catch(Exception e) {throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {registry.add("report.jwks-uri",()->"http://127.0.0.1:"+JWKS.getAddress().getPort()+"/jwks");}
    @AfterAll static void stop() {JWKS.stop(0);}
    @Autowired MockMvc mvc;
    @MockitoBean TargetClient targets;
    @MockitoBean EvidenceStorage storage;
    String token(String role,String issuer,String audience,long expiry,RSAKey key) throws Exception {
        var now=Instant.now();var claims=new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer(issuer).audience(audience)
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(expiry))).jwtID(UUID.randomUUID().toString())
            .claim("token_use","access").claim("sid",UUID.randomUUID().toString()).claim("ver",1).claim("roles",List.of(role)).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("report-test").type(new JOSEObjectType("at+jwt")).build(),claims);
        jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    String token(String role) throws Exception {return token(role,"baotin-auth","baotin-api",300,KEY);}
    @Test void anonymousAndSpoofedHeadersCannotAccessReports() throws Exception {
        mvc.perform(get("/api/v1/reports/me").header("X-User-Id",UUID.randomUUID()).header("X-Role","ADMIN")).andExpect(status().isUnauthorized());
    }
    @Test void realSignedUserCannotEscalateToModerator() throws Exception {
        mvc.perform(get("/api/v1/moderation/reports").header("Authorization","Bearer "+token("USER")).header("X-Role","ADMIN")).andExpect(status().isForbidden());
    }
    @Test void sourceScanResolutionUsesJwtOwnerRatherThanRequestIdentity() throws Exception {
        String access=token("USER");
        UUID owner=UUID.fromString(SignedJWT.parse(access).getJWTClaimsSet().getSubject());
        UUID scan=UUID.randomUUID();
        org.mockito.Mockito.when(targets.resolve(org.mockito.ArgumentMatchers.eq("URL"),org.mockito.ArgumentMatchers.eq("https://example.test/"),org.mockito.ArgumentMatchers.eq(scan),org.mockito.ArgumentMatchers.eq(owner)))
            .thenReturn(new TargetClient.Target(UUID.randomUUID(),"URL","https://example.test/"));
        String body="{\"targetType\":\"URL\",\"targetValue\":\"https://example.test/\",\"category\":\"PHISHING_URL\",\"title\":\"Suspicious website\",\"description\":\"The website requests banking credentials.\",\"scanId\":\""+scan+"\"}";
        mvc.perform(post("/api/v1/reports").header("Authorization","Bearer "+access).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.scanId").value(scan.toString())).andExpect(jsonPath("$.reporterUserId").value(owner.toString()));
        org.mockito.Mockito.verify(targets).resolve("URL","https://example.test/",scan,owner);
    }
    @Test void moderatorAndAdminCanReadQueue() throws Exception {
        for(String role:List.of("MODERATOR","ADMIN")) mvc.perform(get("/api/v1/moderation/reports").header("Authorization","Bearer "+token(role))).andExpect(status().isOk());
    }
    @Test void analyticsInternalEndpointRequiresModeratorOrAdmin() throws Exception {
        mvc.perform(get("/internal/v1/analytics/moderation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/analytics/moderation")
                .header("Authorization", "Bearer " + token("USER"))).andExpect(status().isForbidden());
        for (String role : List.of("MODERATOR", "ADMIN")) {
            mvc.perform(get("/internal/v1/analytics/moderation")
                    .header("Authorization", "Bearer " + token(role))).andExpect(status().isOk());
        }
    }
    @Test void invalidIssuerAudienceExpiryAndSignatureAreRejected() throws Exception {
        for(String jwt:List.of(token("ADMIN","evil","baotin-api",300,KEY),token("ADMIN","baotin-auth","evil",300,KEY),token("ADMIN","baotin-auth","baotin-api",-120,KEY),token("ADMIN","baotin-auth","baotin-api",3600,KEY),token("ADMIN","baotin-auth","baotin-api",300,new RSAKeyGenerator(2048).generate())))
            mvc.perform(get("/api/v1/reports/me").header("Authorization","Bearer "+jwt)).andExpect(status().isUnauthorized());
    }
    @Test void inputPaginationAndMissingIdempotencyAreValidated() throws Exception {
        String auth="Bearer "+token("USER");
        mvc.perform(get("/api/v1/reports/me?size=1000").header("Authorization",auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/me?page=-1").header("Authorization",auth)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reports").header("Authorization",auth).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reports").header("Authorization",auth).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reports/"+UUID.randomUUID()+"/resubmit").header("Authorization",auth).contentType("application/json").content("{\"note\":\"Additional information\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/moderation/reports/"+UUID.randomUUID()+"/start-review").header("Authorization","Bearer "+token("MODERATOR")).contentType("application/json").content("{\"note\":\"Review\"}")).andExpect(status().isBadRequest());
    }
    @Test void healthIsPublicAndInternalPathsAreClosed() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/internal/v1/reports").header("Authorization","Bearer "+token("ADMIN"))).andExpect(status().isForbidden());
    }
}
