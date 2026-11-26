package com.baotin.analytics.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baotin.analytics.api.AnalyticsController;
import com.baotin.analytics.api.ApiProblems;
import com.baotin.analytics.api.CorrelationIdFilter;
import com.baotin.analytics.application.AnalyticsService;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Actual signature/JWKS/decoder/filter chain; no mocked JWT authentication. */
@WebMvcTest(AnalyticsController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({SecurityConfig.class, ApiProblems.class, CorrelationIdFilter.class})
class AuthAccessTokenTests {
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    private static final String[] PATHS = {
        "/api/v1/analytics/admin/summary",
        "/api/v1/analytics/admin/queue",
        "/api/v1/analytics/admin/counts",
        "/api/v1/analytics/admin/trends"
    };

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("ephemeral-test-key").generate();
            byte[] publicKeys = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, publicKeys.length);
                try (var body = exchange.getResponseBody()) { body.write(publicKeys); }
            });
            JWKS.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("analytics.security.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        properties.add("analytics.security.issuer", () -> "baotin-auth");
        properties.add("analytics.security.audience", () -> "baotin-api");
    }

    @AfterAll
    static void closeJwks() { JWKS.stop(0); }

    @Autowired private MockMvc mvc;
    @MockitoBean private AnalyticsService service;

    private String token(String role, String type, String issuer, String audience,
                         String use, Instant expiry, JWSAlgorithm algorithm, RSAKey signer) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer).audience(audience).subject(UUID.randomUUID().toString())
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(expiry))
                .claim("sid", UUID.randomUUID().toString()).claim("ver", 1)
                .claim("roles", List.of(role)).claim("token_use", use)
                .claim("amr", List.of("pwd", "otp")).claim("auth_time", now.getEpochSecond()).build();
        var header = new JWSHeader.Builder(algorithm).keyID(KEY.getKeyID());
        if (type != null) header.type(new JOSEObjectType(type));
        var jwt = new SignedJWT(header.build(), claims);
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    private String access(String role) throws Exception {
        return token(role, "at+jwt", "baotin-auth", "baotin-api", "access",
                Instant.now().plusSeconds(590), JWSAlgorithm.RS256, KEY);
    }

    @Test
    void authShapedAdminAndModeratorAccessTokensReachAllAnalyticsEndpoints() throws Exception {
        for (String role : List.of("ADMIN", "MODERATOR")) {
            String bearer = access(role);
            for (String path : PATHS) {
                mvc.perform(get(path).header("Authorization", "Bearer " + bearer)).andExpect(status().isOk());
            }
        }
    }

    @Test
    void signedUserAccessTokenIsForbiddenAtEveryEndpoint() throws Exception {
        String bearer = access("USER");
        for (String path : PATHS) {
            mvc.perform(get(path).header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden());
        }
    }

    @Test
    void invalidTokenTypesClaimsSignaturesAndAlgorithmsAreRejected() throws Exception {
        Instant future = Instant.now().plusSeconds(590);
        RSAKey otherKey = new RSAKeyGenerator(2048).generate();
        List<String> invalid = List.of(
                token("ADMIN", "JWT", "baotin-auth", "baotin-api", "access", future, JWSAlgorithm.RS256, KEY),
                token("ADMIN", null, "baotin-auth", "baotin-api", "access", future, JWSAlgorithm.RS256, KEY),
                token("ADMIN", "at+jwt", "wrong-issuer", "baotin-api", "access", future, JWSAlgorithm.RS256, KEY),
                token("ADMIN", "at+jwt", "baotin-auth", "wrong-audience", "access", future, JWSAlgorithm.RS256, KEY),
                token("ADMIN", "at+jwt", "baotin-auth", "baotin-api", "refresh", future, JWSAlgorithm.RS256, KEY),
                token("ADMIN", "at+jwt", "baotin-auth", "baotin-api", "access", Instant.now().minusSeconds(120), JWSAlgorithm.RS256, KEY),
                token("ADMIN", "at+jwt", "baotin-auth", "baotin-api", "access", future, JWSAlgorithm.RS256, otherKey),
                token("ADMIN", "at+jwt", "baotin-auth", "baotin-api", "access", future, JWSAlgorithm.RS512, KEY));
        for (String bearer : invalid) {
            mvc.perform(get(PATHS[0]).header("Authorization", "Bearer " + bearer)).andExpect(status().isUnauthorized());
        }
    }
}
