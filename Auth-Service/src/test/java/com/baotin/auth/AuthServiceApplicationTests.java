package com.baotin.auth;

import com.baotin.auth.application.*;
import com.baotin.auth.api.ApiFailure;
import com.baotin.auth.security.*;
import com.baotin.auth.oauth.OAuthService;
import com.baotin.auth.phone.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class AuthServiceApplicationTests {
    static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17-alpine");
    static final String RUNTIME_PASSWORD=AuthCrypto.token();
    static final String MIGRATION_PASSWORD=AuthCrypto.token();
    static final String OPERATOR_PASSWORD=AuthCrypto.token();
    static {
        PG.start();
        try(var connection=java.sql.DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var statement=connection.createStatement()){
            statement.execute(resource("db/provision/before-migrate.sql"));
            statement.execute("ALTER ROLE baotin_auth PASSWORD '"+RUNTIME_PASSWORD+"'");
            statement.execute("ALTER ROLE baotin_auth_migrator PASSWORD '"+MIGRATION_PASSWORD+"'");
            statement.execute("ALTER ROLE baotin_auth_operator PASSWORD '"+OPERATOR_PASSWORD+"'");
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static String resource(String name)throws Exception{
        try(var in=AuthServiceApplicationTests.class.getClassLoader().getResourceAsStream(name)){
            if(in==null)throw new IllegalStateException("Resource missing: "+name);return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @BeforeAll void runtimeGrants()throws Exception{
        try(var connection=java.sql.DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var statement=connection.createStatement()){
            statement.execute(resource("db/provision/after-migrate.sql"));
            statement.execute("CREATE SCHEMA report;CREATE TABLE report.private_test(value TEXT);INSERT INTO report.private_test VALUES ('private');REVOKE ALL ON SCHEMA report FROM PUBLIC");
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.flyway.enabled",()->true); // Test harness runs migrations before granting runtime permissions.
        registry.add("spring.datasource.url",PG::getJdbcUrl);registry.add("spring.datasource.username",()->"baotin_auth");registry.add("spring.datasource.password",()->RUNTIME_PASSWORD);
        registry.add("spring.flyway.user",()->"baotin_auth_migrator");registry.add("spring.flyway.password",()->MIGRATION_PASSWORD);registry.add("spring.flyway.create-schemas",()->false);
        registry.add("auth.oauth.google.client-id",()->"google-client");registry.add("auth.oauth.google.client-secret",()->"google-secret");registry.add("auth.oauth.google.callback-uri",()->OAuthFixture.base()+"/callback/google");
        registry.add("auth.oauth.google.authorization-uri",()->OAuthFixture.base()+"/authorize/google");registry.add("auth.oauth.google.token-uri",()->OAuthFixture.base()+"/google/token");registry.add("auth.oauth.google.jwks-uri",()->OAuthFixture.base()+"/jwks");
        registry.add("auth.oauth.facebook.client-id",()->"facebook-client");registry.add("auth.oauth.facebook.client-secret",()->"facebook-secret");registry.add("auth.oauth.facebook.callback-uri",()->OAuthFixture.base()+"/callback/facebook");
        registry.add("auth.oauth.facebook.authorization-uri",()->OAuthFixture.base()+"/authorize/facebook");registry.add("auth.oauth.facebook.token-uri",()->OAuthFixture.base()+"/facebook/token");registry.add("auth.oauth.facebook.graph-uri",()->OAuthFixture.base()+"/graph");
        registry.add("auth.firebase.enabled",()->true);registry.add("auth.firebase.project-id",()->"phone-dev");registry.add("auth.firebase.test-phones",()->"+84900000001,+84900000002,+84900000003,+84900000004");
    }
    @Autowired AuthService service;
    @Autowired IdentityStore store;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired JwtTokens configuredJwt;
    @Autowired SigningKeys keys;
    @Autowired AdminService admin;
    @Autowired OAuthService oauth;
    @Autowired PhoneService phoneService;
    @Autowired DeliveryQueue deliveryQueue;
    @Autowired SecurityNotices notices;
    @org.springframework.test.context.bean.override.mockito.MockitoBean FirebasePhoneVerifier firebaseAdapter;
    private final org.springframework.jdbc.core.JdbcTemplate provisioning=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
    @LocalServerPort int port;
    private static final String PASSWORD="correct horse battery staple";
    private UUID create(){String name="u"+UUID.randomUUID().toString().replace("-","").substring(0,12);return service.register(name,name+"@example.test",PASSWORD,"Test user").id();}
    private AuthService.Login login(UUID id){return service.login(service.profile(id).username(),PASSWORD);}
    private HttpResponse<String> request(String method,String path,String body,String access)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(access!=null)builder.header("Authorization","Bearer "+access);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void disabledSmtpDoesNotMakeAuthUnhealthy()throws Exception{
        var health=request("GET","/actuator/health",null,null);
        assertEquals(200,health.statusCode());
        assertTrue(health.body().contains("\"status\":\"UP\""));
    }
    @Test void directJarDatabaseProbeUsesReadOnlyDedicatedRuntimeIdentity()throws Exception{
        var state=com.baotin.auth.operator.RuntimeDatabaseState.read(PG.getJdbcUrl(),"baotin_auth",RUNTIME_PASSWORD);
        assertEquals("10",state.get("schemaRevision"));assertEquals("2",state.get("readerFloor"));assertEquals("BK1",state.get("cipherFloor"));
        assertThrows(IllegalStateException.class,()->com.baotin.auth.operator.RuntimeDatabaseState.read(PG.getJdbcUrl(),"baotin_auth_migrator",MIGRATION_PASSWORD));
        assertThrows(IllegalArgumentException.class,()->com.baotin.auth.operator.RuntimeDatabaseState.main(new String[]{"unreviewed SQL"}));
    }
    @Test void jwtRefreshReuseAndLogoutAreEnforced()throws Exception{
        UUID id=create();var first=login(id).tokens();Jwt jwt=decoder.decode(first.accessToken());
        assertEquals(id.toString(),jwt.getSubject());assertEquals("access",jwt.getClaimAsString("token_use"));
        assertEquals(200,request("GET","/api/v1/auth/me",null,first.accessToken()).statusCode());
        var rotated=service.refresh(first.refreshToken());assertNotEquals(first.refreshToken(),rotated.refreshToken());
        assertThrows(ApiFailure.class,()->service.refresh(first.refreshToken()));
        assertEquals(401,request("GET","/api/v1/auth/me",null,rotated.accessToken()).statusCode());
        assertThrows(ApiFailure.class,()->service.refresh(rotated.refreshToken()));
        var next=login(id).tokens();service.logout(id,UUID.fromString(decoder.decode(next.accessToken()).getClaimAsString("sid")));
        assertEquals(401,request("GET","/api/v1/auth/me",null,next.accessToken()).statusCode());
    }
    @Test void mfaEnrollmentRecoveryReplayAndResetDoNotBypassMfa()throws Exception{
        UUID id=create();store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);
        var before=login(id).tokens();var enrollment=service.enroll(id,PASSWORD);
        assertTrue(enrollment.otpauthUri().startsWith("otpauth://totp/"));
        byte[] secret=keys.decrypt((byte[])store.jdbc().queryForMap("SELECT encrypted_secret FROM auth.mfa_enrollments WHERE user_id=?",id).get("encrypted_secret"));
        var codes=service.confirmEnrollment(id,AuthCrypto.totp(secret,Instant.now().getEpochSecond()/30));
        assertEquals(10,codes.size());assertEquals(401,request("GET","/api/v1/auth/me",null,before.accessToken()).statusCode());
        var challenge=login(id);assertNull(challenge.tokens());assertTrue(challenge.mfaRequired());
        assertThrows(ApiFailure.class,()->service.completeChallenge(challenge.challenge(),"000000invalid"));
        var tokens=service.completeChallenge(challenge.challenge(),codes.getFirst());assertNotNull(tokens.accessToken());
        assertEquals(200,request("GET","/api/v1/auth/me",null,tokens.accessToken()).statusCode());
        assertThrows(ApiFailure.class,()->service.completeChallenge(challenge.challenge(),codes.get(1)));
        var second=login(id);assertThrows(ApiFailure.class,()->service.completeChallenge(second.challenge(),codes.getFirst()));
        assertEquals(1,store.jdbc().queryForObject("SELECT count(*) FROM auth.recovery_codes WHERE user_id=? AND consumed_at IS NOT NULL",Integer.class,id));
        service.forgot(service.profile(id).email());
        var payloads=store.jdbc().queryForList("SELECT encrypted_payload FROM auth.mail_outbox ORDER BY created_at DESC");
        String payload=new String(keys.decrypt((byte[])payloads.getFirst().get("encrypted_payload")),java.nio.charset.StandardCharsets.UTF_8);
        String resetToken=payload.substring(payload.lastIndexOf(": ")+2);
        service.resetPassword(resetToken,PASSWORD);
        assertTrue(service.profile(id).mfaEnabled());assertTrue(login(id).mfaRequired());
        assertThrows(ApiFailure.class,()->service.resetPassword(resetToken,PASSWORD));
    }
    @Test void rejectsMassAssignmentCrossUserSessionsAndForgedRoles()throws Exception{
        UUID id=create();UUID other=create();var a=login(id).tokens();var b=login(other).tokens();
        assertEquals(400,request("PATCH","/api/v1/auth/me","{\"displayName\":\"Nice\",\"role\":\"ADMIN\"}",a.accessToken()).statusCode());
        assertEquals("USER",service.profile(id).role());
        UUID otherSid=UUID.fromString(decoder.decode(b.accessToken()).getClaimAsString("sid"));
        service.logout(id,otherSid);assertEquals(200,request("GET","/api/v1/auth/me",null,b.accessToken()).statusCode());
        Jwt legitimate=decoder.decode(a.accessToken());
        var forgedClaims=JwtClaimsSet.builder().claims(c->c.putAll(legitimate.getClaims())).claim("roles",List.of("ADMIN")).build();
        String forged=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").type("at+jwt").build(),forgedClaims)).getTokenValue();
        assertEquals(401,request("GET","/api/v1/auth/me",null,forged).statusCode());
        assertEquals(403,request("GET","/actuator/env",null,a.accessToken()).statusCode());
        assertEquals(200,request("GET","/.well-known/jwks.json",null,null).statusCode());
        assertFalse(request("GET","/.well-known/jwks.json",null,null).body().contains("\"d\":"));
    }
    @Test void concurrentRefreshRotatesOnceAndReuseRevokesFamily()throws Exception{
        UUID id=create();var tokens=login(id).tokens();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> task=()->{start.await();try{service.refresh(tokens.refreshToken());return true;}catch(ApiFailure e){return false;}};
            var one=executor.submit(task);var two=executor.submit(task);start.countDown();
            assertNotEquals(one.get(),two.get());
        }
        assertEquals(401,request("GET","/api/v1/auth/me",null,tokens.accessToken()).statusCode());
    }
    @Test void privilegedAccountRequiresMfaAndChallengesStopAfterFiveAttempts(){
        UUID id=create();provisioning.update("UPDATE auth.users SET role='ADMIN' WHERE id=?",id);
        assertEquals("MFA_ENROLLMENT_REQUIRED",assertThrows(ApiFailure.class,()->login(id)).code());
        provisioning.update("UPDATE auth.users SET role='USER',email_verified=true WHERE id=?",id);
        service.enroll(id,PASSWORD);byte[] secret=keys.decrypt((byte[])store.jdbc().queryForMap("SELECT encrypted_secret FROM auth.mfa_enrollments WHERE user_id=?",id).get("encrypted_secret"));
        var codes=service.confirmEnrollment(id,AuthCrypto.totp(secret,Instant.now().getEpochSecond()/30));
        var c=login(id);
        for(int i=0;i<5;i++)assertThrows(ApiFailure.class,()->service.completeChallenge(c.challenge(),"invalid"));
        assertThrows(ApiFailure.class,()->service.completeChallenge(c.challenge(),codes.getFirst()));
    }
    @Test void webCookieTransportRequiresCsrfOriginAndCannotBeExchangedAsNative()throws Exception{
        UUID id=create();String username=service.profile(id).username();
        String body="{\"identifier\":\""+username+"\",\"password\":\""+PASSWORD+"\"}";
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/web/login"))
            .header("Origin","https://localhost:9444").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());assertFalse(response.body().contains("refreshToken"));
        var cookies=response.headers().allValues("Set-Cookie");assertEquals(2,cookies.size());
        String refreshCookie=cookies.stream().filter(c->c.startsWith("__Host-baotin-refresh=")).findFirst().orElseThrow();
        assertTrue(refreshCookie.contains("Secure"));assertTrue(refreshCookie.contains("HttpOnly"));assertTrue(refreshCookie.contains("SameSite=Lax"));
        String refresh=refreshCookie.split(";",2)[0].split("=",2)[1];
        String csrf=cookies.stream().filter(c->c.startsWith("__Host-baotin-csrf=")).findFirst().orElseThrow().split(";",2)[0].split("=",2)[1];
        assertThrows(ApiFailure.class,()->service.refresh(refresh));
        assertThrows(ApiFailure.class,()->service.webRefresh(refresh,"wrong"));
        assertNotNull(service.webRefresh(refresh,csrf));
        assertEquals(403,request("POST","/api/v1/auth/web/login",body,null).statusCode());
    }
    @Test void validTotpWorksOnceAndVerificationTokensAreSingleUse(){
        UUID id=create();
        var encrypted=store.jdbc().queryForList("SELECT encrypted_payload FROM auth.mail_outbox ORDER BY created_at DESC").getFirst();
        String payload=new String(keys.decrypt((byte[])encrypted.get("encrypted_payload")),java.nio.charset.StandardCharsets.UTF_8);
        String token=payload.substring(payload.lastIndexOf(": ")+2);service.verifyEmail(token);
        assertThrows(ApiFailure.class,()->service.verifyEmail(token));
        service.enroll(id,PASSWORD);
        byte[] secret=keys.decrypt((byte[])store.jdbc().queryForMap("SELECT encrypted_secret FROM auth.mfa_enrollments WHERE user_id=?",id).get("encrypted_secret"));
        long step=Instant.now().getEpochSecond()/30;
        service.confirmEnrollment(id,AuthCrypto.totp(secret,step-1));
        var login=login(id);assertNotNull(service.completeChallenge(login.challenge(),AuthCrypto.totp(secret,step)));
        var second=login(id);assertThrows(ApiFailure.class,()->service.completeChallenge(second.challenge(),AuthCrypto.totp(secret,step)));
    }
    @Test void disabledAndDemotedAccountsCannotUseOldTokens()throws Exception{
        UUID id=create();var tokens=login(id).tokens();
        provisioning.update("UPDATE auth.users SET status='LOCKED' WHERE id=?",id);
        assertEquals(401,request("GET","/api/v1/auth/me",null,tokens.accessToken()).statusCode());
        assertThrows(ApiFailure.class,()->service.refresh(tokens.refreshToken()));
        provisioning.update("UPDATE auth.users SET status='ACTIVE',security_version=security_version+1 WHERE id=?",id);
        assertEquals(401,request("GET","/api/v1/auth/me",null,tokens.accessToken()).statusCode());
    }
    @Test void decoderRejectsWrongAudienceIssuerUseAlgorithmAndKey(){
        UUID id=create();var valid=decoder.decode(login(id).tokens().accessToken());
        for(String field:List.of("iss","aud","token_use")){
            var claims=JwtClaimsSet.builder().claims(c->c.putAll(valid.getClaims())).claim(field,field.equals("aud")?List.of("wrong"):"wrong").build();
            String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").type("at+jwt").build(),claims)).getTokenValue();
            assertThrows(JwtException.class,()->decoder.decode(token));
        }
        String wrongKid=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").type("at+jwt").build(),JwtClaimsSet.builder().claims(c->c.putAll(valid.getClaims())).issuedAt(Instant.now().minusSeconds(660)).notBefore(Instant.now().minusSeconds(660)).expiresAt(Instant.now().minusSeconds(60)).build())).getTokenValue();
        assertThrows(JwtException.class,()->decoder.decode(wrongKid));
        assertThrows(JwtException.class,()->decoder.decode("eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.")); // gitleaks:allow - intentionally unsigned alg=none rejection fixture, not a valid credential.
    }
    @Test void runtimeDatabaseRoleCannotEscalateOrReadOtherSchema(){
        UUID id=create();
        for(String sql:List.of("CREATE TABLE auth.attack(id INT)","DROP TABLE auth.users","CREATE ROLE attack SUPERUSER","SET ROLE baotin_auth_migrator","SELECT * FROM report.private_test","UPDATE auth.users SET role='ADMIN'","UPDATE auth.users SET status='LOCKED'","UPDATE auth.audit_events SET event_type='HIDDEN'","DELETE FROM auth.audit_events","UPDATE auth.flyway_schema_history SET success=false"))
            assertThrows(org.springframework.dao.DataAccessException.class,()->store.jdbc().execute(sql),sql);
        assertThrows(org.springframework.dao.DataAccessException.class,()->store.jdbc().queryForObject("SELECT auth.operator_admin('request',?,'unauthorized runtime attempt')",Object.class,id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->store.jdbc().queryForObject("SELECT auth.set_managed_role(?,?,'ADMIN')",Object.class,id,create()));
        assertEquals("baotin_auth",store.jdbc().queryForObject("SELECT session_user",String.class));
        assertFalse(store.jdbc().queryForObject("SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
    }
    @Test void operatorGrantRequiresVerifiedMfaAndApplicationAdminCannotGrantAdmin()throws Exception{
        UUID actor=create();UUID target=create();UUID other=create();
        provisioning.queryForObject("SELECT auth.operator_admin('request',?,'approved initial administrator request')",Object.class,actor);
        assertEquals("USER",service.profile(actor).role());
        assertThrows(org.springframework.dao.DataAccessException.class,()->provisioning.queryForObject("SELECT auth.operator_admin('activate',?,'verified admin activation request')",Object.class,actor));
        store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",actor);
        service.enroll(actor,PASSWORD);byte[] secret=keys.decrypt((byte[])store.jdbc().queryForMap("SELECT encrypted_secret FROM auth.mfa_enrollments WHERE user_id=?",actor).get("encrypted_secret"));
        long step=Instant.now().getEpochSecond()/30;
        var recovery=service.confirmEnrollment(actor,AuthCrypto.totp(secret,step-1));
        provisioning.queryForObject("SELECT auth.operator_admin('activate',?,'verified admin activation request')",Object.class,actor);
        var challenged=login(actor);var tokens=service.completeChallenge(challenged.challenge(),recovery.getFirst());
        UUID sid=UUID.fromString(decoder.decode(tokens.accessToken()).getClaimAsString("sid"));
        assertEquals("STEP_UP_REQUIRED",assertThrows(ApiFailure.class,()->admin.role(actor,sid,target,"USER")).code());
        assertThrows(ApiFailure.class,()->admin.stepUp(actor,sid,recovery.get(1)));
        admin.stepUp(actor,sid,AuthCrypto.totp(secret,step));
        assertEquals("ROLE_TRANSITION_DENIED",assertThrows(ApiFailure.class,()->admin.role(actor,sid,target,"ADMIN")).code());
        assertThrows(ApiFailure.class,()->admin.role(actor,sid,actor,"USER"));
        assertThrows(ApiFailure.class,()->admin.role(actor,sid,target,"MODERATOR"));
        store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",target);
        service.enroll(target,PASSWORD);byte[] targetSecret=keys.decrypt((byte[])store.jdbc().queryForMap("SELECT encrypted_secret FROM auth.mfa_enrollments WHERE user_id=?",target).get("encrypted_secret"));
        var targetRecovery=service.confirmEnrollment(target,AuthCrypto.totp(targetSecret,Instant.now().getEpochSecond()/30));
        admin.role(actor,sid,target,"MODERATOR");assertEquals("MODERATOR",service.profile(target).role());
        var moderatorLogin=login(target);var moderatorToken=service.completeChallenge(moderatorLogin.challenge(),targetRecovery.getFirst());
        assertEquals(403,request("GET","/api/v1/auth/admin/users",null,moderatorToken.accessToken()).statusCode());
        assertEquals(403,request("PATCH","/api/v1/auth/admin/users/"+other+"/role","{\"role\":\"MODERATOR\"}",moderatorToken.accessToken()).statusCode());
        assertEquals(401,request("GET","/api/v1/auth/admin/users",null,null).statusCode());
        assertEquals(200,request("GET","/api/v1/auth/admin/users",null,tokens.accessToken()).statusCode());
        store.jdbc().update("UPDATE auth.refresh_sessions SET step_up_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(301)),sid);
        assertEquals(403,request("GET","/api/v1/auth/admin/users",null,tokens.accessToken()).statusCode());
        store.jdbc().update("UPDATE auth.refresh_sessions SET step_up_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now()),sid);
        admin.status(actor,sid,target,"LOCKED");
        assertEquals(403,request("GET","/api/v1/auth/admin/users",null,login(other).tokens().accessToken()).statusCode());
        store.jdbc().update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now()),sid);
        assertEquals(401,request("GET","/api/v1/auth/admin/users",null,tokens.accessToken()).statusCode());
        provisioning.queryForObject("SELECT auth.operator_admin('recover',?,'owner verified lost all MFA factors')",Object.class,actor);
        assertEquals("USER",service.profile(actor).role());assertFalse(service.profile(actor).mfaEnabled());
        assertEquals(401,request("GET","/api/v1/auth/me",null,tokens.accessToken()).statusCode());
        assertEquals(401,request("GET","/api/v1/auth/admin/users",null,tokens.accessToken()).statusCode());
        assertThrows(org.springframework.dao.DataAccessException.class,()->provisioning.queryForObject("SELECT auth.operator_admin('activate',?,'restore admin without new authenticator')",Object.class,actor));
    }
    @Test void googlePkceExchangeIsSingleUseAndDoesNotAutoLinkExistingEmail(){
        String verifier=AuthCrypto.token(),subject="g"+UUID.randomUUID(),email=UUID.randomUUID()+"@example.test";
        var start=oauth.start("GOOGLE","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),null,null);
        String code=OAuthFixture.code(start,subject,email,null,"google-client","facebook-client");
        String redirect=oauth.callback("google",OAuthFixture.state(start),code,null),ticket=OAuthFixture.ticket(redirect);
        assertThrows(ApiFailure.class,()->oauth.exchange(ticket,AuthCrypto.token(),"NATIVE"));
        var result=oauth.exchange(ticket,verifier,"NATIVE");assertNotNull(result.login().tokens());
        var token=decoder.decode(result.login().tokens().accessToken());assertEquals(List.of("federated"),token.getClaimAsStringList("amr"));
        UUID id=UUID.fromString(token.getSubject());assertTrue(service.profile(id).emailVerified());
        service.initializePassword(id,UUID.fromString(token.getClaimAsString("sid")),PASSWORD,null);
        assertNotNull(service.login(email,PASSWORD).tokens());
        assertThrows(ApiFailure.class,()->oauth.exchange(ticket,verifier,"NATIVE"));
        assertThrows(ApiFailure.class,()->oauth.callback("google",OAuthFixture.state(start),code,null));
        UUID existing=create();String existingEmail=service.profile(existing).email();
        var duplicate=oauth.start("GOOGLE","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),null,null);
        String duplicateCode=OAuthFixture.code(duplicate,"other-subject"+UUID.randomUUID(),existingEmail,null,"google-client","facebook-client");
        String duplicateTicket=OAuthFixture.ticket(oauth.callback("google",OAuthFixture.state(duplicate),duplicateCode,null));
        assertEquals("ACCOUNT_LINK_REQUIRED",assertThrows(ApiFailure.class,()->oauth.exchange(duplicateTicket,verifier,"NATIVE")).code());
        assertEquals(0,provisioning.queryForObject("SELECT count(*) FROM auth.oauth_accounts WHERE user_id=?",Integer.class,existing));
    }
    @Test void oauthRejectsNonceAudienceStateReturnUriAndBrowserBinding(){
        String verifier=AuthCrypto.token();
        assertThrows(ApiFailure.class,()->oauth.start("GOOGLE","NATIVE","https://evil.test/callback",OAuthService.challenge(verifier),null,null));
        for(boolean wrongNonce:List.of(true,false)){
            var start=oauth.start("GOOGLE","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),null,null);
            String code=OAuthFixture.code(start,UUID.randomUUID().toString(),UUID.randomUUID()+"@example.test",wrongNonce?"wrong":null,wrongNonce?"google-client":"wrong-audience","facebook-client");
            assertThrows(ApiFailure.class,()->oauth.callback("google",OAuthFixture.state(start),code,null));
            assertThrows(ApiFailure.class,()->oauth.callback("google",OAuthFixture.state(start),code,null));
        }
        assertThrows(ApiFailure.class,()->oauth.callback("google",AuthCrypto.token(),"unknown",null));
        var web=oauth.start("GOOGLE","WEB","https://localhost:9444/auth/callback",OAuthService.challenge(verifier),null,null);
        String code=OAuthFixture.code(web,UUID.randomUUID().toString(),UUID.randomUUID()+"@example.test",null,"google-client","facebook-client");
        assertThrows(ApiFailure.class,()->oauth.callback("google",OAuthFixture.state(web),code,"wrong"));
        String ticket=OAuthFixture.ticket(oauth.callback("google",OAuthFixture.state(web),code,web.binding()));
        assertThrows(ApiFailure.class,()->oauth.exchange(ticket,verifier,"NATIVE"));
        assertNotNull(oauth.exchange(ticket,verifier,"WEB").login().tokens());
    }
    @Test void explicitProviderLinkIsBoundToAuthenticatedOwnerAndSecurityVersion(){
        UUID id=create();store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);var session=decoder.decode(login(id).tokens().accessToken());
        String verifier=AuthCrypto.token(),subject=UUID.randomUUID().toString();
        var start=oauth.start("GOOGLE","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),id,UUID.fromString(session.getClaimAsString("sid")));
        String code=OAuthFixture.code(start,subject,"different@example.test",null,"google-client","facebook-client");
        var result=oauth.exchange(OAuthFixture.ticket(oauth.callback("google",OAuthFixture.state(start),code,null)),verifier,"NATIVE");assertTrue(result.linked());
        assertEquals(id.toString(),decoder.decode(service.providerLogin("GOOGLE",subject,"different@example.test",true,"Provider","NATIVE").tokens().accessToken()).getSubject());
        var expired=oauth.start("FACEBOOK","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),id,UUID.fromString(session.getClaimAsString("sid")));
        String fc=OAuthFixture.code(expired,UUID.randomUUID().toString(),"different@example.test",null,"google-client","facebook-client");
        String ticket=OAuthFixture.ticket(oauth.callback("facebook",OAuthFixture.state(expired),fc,null));service.logoutAll(id);
        assertThrows(ApiFailure.class,()->oauth.exchange(ticket,verifier,"NATIVE"));
    }
    @Test void facebookChecksApplicationBindingAndNeverMarksEmailVerified(){
        String verifier=AuthCrypto.token();
        var bad=oauth.start("FACEBOOK","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),null,null);
        String bc=OAuthFixture.code(bad,UUID.randomUUID().toString(),UUID.randomUUID()+"@example.test",null,"google-client","wrong-app");
        assertThrows(ApiFailure.class,()->oauth.callback("facebook",OAuthFixture.state(bad),bc,null));
        var good=oauth.start("FACEBOOK","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),null,null);
        String gc=OAuthFixture.code(good,UUID.randomUUID().toString(),UUID.randomUUID()+"@example.test",null,"google-client","facebook-client");
        var result=oauth.exchange(OAuthFixture.ticket(oauth.callback("facebook",OAuthFixture.state(good),gc,null)),verifier,"NATIVE");
        UUID id=UUID.fromString(decoder.decode(result.login().tokens().accessToken()).getSubject());assertFalse(service.profile(id).emailVerified());
    }
    @Test void providerLoginCannotBypassConfiguredMfa(){
        UUID id=create();store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);String subject=UUID.randomUUID().toString();
        var jwt=decoder.decode(login(id).tokens().accessToken());service.linkProvider(id,UUID.fromString(jwt.getClaimAsString("sid")),0,"GOOGLE",subject);
        service.enroll(id,PASSWORD);byte[] secret=keys.decrypt((byte[])store.jdbc().queryForMap("SELECT encrypted_secret FROM auth.mfa_enrollments WHERE user_id=?",id).get("encrypted_secret"));
        var recovery=service.confirmEnrollment(id,AuthCrypto.totp(secret,Instant.now().getEpochSecond()/30));
        var login=service.providerLogin("GOOGLE",subject,service.profile(id).email(),true,"Provider","NATIVE");assertTrue(login.mfaRequired());assertNull(login.tokens());
        assertEquals(List.of("federated","otp"),decoder.decode(service.completeChallenge(login.challenge(),recovery.getFirst()).accessToken()).getClaimAsStringList("amr"));
    }
    @Test void singleSessionLogoutCancelsPendingProviderLink(){
        UUID id=create();store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);var jwt=decoder.decode(login(id).tokens().accessToken());UUID sid=UUID.fromString(jwt.getClaimAsString("sid"));
        String verifier=AuthCrypto.token();var start=oauth.start("GOOGLE","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),id,sid);
        String code=OAuthFixture.code(start,UUID.randomUUID().toString(),"separate@example.test",null,"google-client","facebook-client");
        String ticket=OAuthFixture.ticket(oauth.callback("google",OAuthFixture.state(start),code,null));
        service.logout(id,sid);assertThrows(ApiFailure.class,()->oauth.exchange(ticket,verifier,"NATIVE"));
    }
    @Test void operatorCliUsesDedicatedRoleAndCannotReadUserTable()throws Exception{
        UUID id=create();
        String classes=java.nio.file.Path.of(com.baotin.auth.operator.AdminOperator.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String driver=java.nio.file.Path.of(Class.forName("org.postgresql.Driver").getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String javaBin=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
        var process=new ProcessBuilder(javaBin,"-cp",classes+java.io.File.pathSeparator+driver,"com.baotin.auth.operator.AdminOperator","request",id.toString(),"approved operator CLI identity check").redirectErrorStream(true);
        process.environment().put("OPERATOR_DB_URL",PG.getJdbcUrl());process.environment().put("OPERATOR_DB_USER","baotin_auth_operator");process.environment().put("OPERATOR_DB_PASSWORD",OPERATOR_PASSWORD);
        var started=process.start();assertTrue(started.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,started.exitValue());
        assertTrue(new String(started.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("OPERATOR_ADMIN_OK action=request"));
        var operator=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),"baotin_auth_operator",OPERATOR_PASSWORD));
        assertThrows(org.springframework.dao.DataAccessException.class,()->operator.execute("SELECT * FROM auth.users"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->operator.execute("UPDATE auth.users SET role='ADMIN'"));
        assertEquals(1,provisioning.queryForObject("SELECT count(*) FROM auth.pending_privileges WHERE user_id=?",Integer.class,id));
    }
    @Test void deniedProviderConsentConsumesStateWithoutIssuingTicket(){
        var start=oauth.start("GOOGLE","NATIVE","baotin://auth/callback",OAuthService.challenge(AuthCrypto.token()),null,null);
        String redirect=oauth.cancel("google",OAuthFixture.state(start),null);
        assertEquals("baotin://auth/callback?error=oauth_denied",redirect);
        assertThrows(ApiFailure.class,()->oauth.cancel("google",OAuthFixture.state(start),null));
    }
    @Test void encryptionRotationCliRewritesAllEncryptedStoresWithoutPlaintextOutput()throws Exception{
        String db="rotate_"+UUID.randomUUID().toString().replace("-","").substring(0,12);provisioning.execute("CREATE DATABASE "+db+" OWNER baotin_auth_migrator");
        String url=PG.getJdbcUrl().substring(0,PG.getJdbcUrl().lastIndexOf('/')+1)+db;
        org.flywaydb.core.Flyway.configure().dataSource(url,"baotin_auth_migrator",MIGRATION_PASSWORD).schemas("auth").load().migrate();
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"baotin_auth_migrator",MIGRATION_PASSWORD));
        byte[] old=AuthCrypto.randomBytes(32),current=AuthCrypto.randomBytes(32);String plaintext="rotation-private-payload";
        byte[] value=AuthCrypto.encrypt(old,plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users(id,username,email,display_name,mfa_secret) VALUES (?,?,?,?,?)",id,"rotation","rotation@example.test","Rotation",value);
        jdbc.update("INSERT INTO auth.mfa_enrollments(user_id,encrypted_secret,expires_at) VALUES (?,?,now()+interval '5 minutes')",id,value);
        jdbc.update("INSERT INTO auth.mail_outbox(id,encrypted_payload) VALUES (?,?)",id,value);
        jdbc.update("INSERT INTO auth.oauth_requests(state_hash,provider,transport,return_uri,client_challenge,nonce,encrypted_verifier,expires_at) VALUES (?, 'GOOGLE','NATIVE','baotin://auth/callback',?,?,?,now()+interval '5 minutes')",AuthCrypto.hash("rotation-state"),AuthCrypto.token(),AuthCrypto.token(),value);
        jdbc.update("INSERT INTO auth.oauth_tickets(ticket_hash,encrypted_payload,client_challenge,transport,expires_at) VALUES (?,?,?,'NATIVE',now()+interval '5 minutes')",AuthCrypto.hash("rotation-ticket"),value,AuthCrypto.token());
        var directory=java.nio.file.Files.createTempDirectory("baotin-key-rotation-test-");
        try{
            var first=directory.resolve("old.bin");var second=directory.resolve("new.bin");java.nio.file.Files.write(first,old);java.nio.file.Files.write(second,current);
            var classes=List.of(com.baotin.auth.operator.RewrapSecrets.class,org.springframework.core.env.StandardEnvironment.class,org.apache.commons.logging.LogFactory.class,Class.forName("org.postgresql.Driver"));
            String cp=classes.stream().map(c->{try{return java.nio.file.Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();}catch(Exception e){throw new IllegalStateException(e);}}).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString(),"-cp",cp,"com.baotin.auth.operator.RewrapSecrets").redirectErrorStream(true);
            process.environment().put("DB_URL",url);process.environment().put("DB_MIGRATION_USER","baotin_auth_migrator");process.environment().put("DB_MIGRATION_PASSWORD",MIGRATION_PASSWORD);
            process.environment().put("AUTH_ENCRYPTION_KEY_ID","new");process.environment().put("AUTH_ENCRYPTION_KEY_PATH",second.toString());process.environment().put("AUTH_LEGACY_ENCRYPTION_KEY_ID","old");process.environment().put("AUTH_PREVIOUS_ENCRYPTION_KEYS","old="+first);
            var started=process.start();assertTrue(started.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));String output=new String(started.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,started.exitValue(),output);assertTrue(output.contains("SECRET_REWRAP_OK changed=5"));assertFalse(output.contains(plaintext));
            var ring=new EncryptionKeyRing("new","new",Map.of("new",current));
            for(String query:List.of("SELECT mfa_secret FROM auth.users","SELECT encrypted_secret FROM auth.mfa_enrollments","SELECT encrypted_payload FROM auth.mail_outbox","SELECT encrypted_verifier FROM auth.oauth_requests","SELECT encrypted_payload FROM auth.oauth_tickets")){
                byte[] cipher=jdbc.queryForObject(query,byte[].class);assertEquals(plaintext,new String(ring.decrypt(cipher),java.nio.charset.StandardCharsets.UTF_8));assertTrue(ring.isCurrent(cipher));
            }
        }finally{try(var files=java.nio.file.Files.walk(directory)){for(var p:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(p);}provisioning.execute("DROP DATABASE "+db+" WITH (FORCE)");}
    }
    @Test void phoneOnlyDevRegistrationVerificationLoginAndReplayAreBoundToOwner(){
        String name="p"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        var user=service.registerContact(name,null,"+84900000001",PASSWORD,"Dev phone");assertNull(user.email());assertFalse(user.phoneVerified());
        assertThrows(ApiFailure.class,()->service.login("+84900000001",PASSWORD));
        var jwt=decoder.decode(login(user.id()).tokens().accessToken());UUID sid=UUID.fromString(jwt.getClaimAsString("sid"));
        String challenge=phoneService.challenge(user.id(),sid,"+84900000001",PASSWORD);
        var proof=new PhoneProofVerifier.Proof("phone-dev","firebase-user-one","+84900000001","phone",Instant.now().getEpochSecond());
        org.mockito.Mockito.when(firebaseAdapter.verify("valid-phone-proof")).thenReturn(proof);
        phoneService.confirm(user.id(),sid,challenge,"valid-phone-proof");assertTrue(service.profile(user.id()).phoneVerified());
        assertThrows(ApiFailure.class,()->service.refresh(login(user.id()).tokens().refreshToken()+"invalid"));
        var byPhone=service.login("+84900000001",PASSWORD);assertNotNull(byPhone.tokens());
        var secondJwt=decoder.decode(byPhone.tokens().accessToken());UUID secondSid=UUID.fromString(secondJwt.getClaimAsString("sid"));
        String secondChallenge=phoneService.challenge(user.id(),secondSid,"+84900000001",PASSWORD);
        assertEquals("PHONE_PROOF_REUSED",assertThrows(ApiFailure.class,()->phoneService.confirm(user.id(),secondSid,secondChallenge,"valid-phone-proof")).code());
        assertEquals(1,store.jdbc().queryForObject("SELECT count(*) FROM auth.phone_proofs WHERE user_id=?",Integer.class,user.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->store.jdbc().execute("DELETE FROM auth.phone_proofs"));
    }
    @Test void phoneProofRejectsWrongProjectProviderNumberFreshnessAndUser(){
        UUID id=create();var jwt=decoder.decode(login(id).tokens().accessToken());UUID sid=UUID.fromString(jwt.getClaimAsString("sid"));String challenge=phoneService.challenge(id,sid,"+84900000002",PASSWORD);
        long now=Instant.now().getEpochSecond();
        var invalid=List.of(new PhoneProofVerifier.Proof("wrong-project","uid","+84900000002","phone",now),new PhoneProofVerifier.Proof("phone-dev","uid","+84900000002","google.com",now),new PhoneProofVerifier.Proof("phone-dev","uid","+84900000003","phone",now),new PhoneProofVerifier.Proof("phone-dev","uid","+84900000002","phone",now-600),new PhoneProofVerifier.Proof("phone-dev","uid","+84900000002","phone",now+120));
        for(var proof:invalid){org.mockito.Mockito.when(firebaseAdapter.verify("invalid")).thenReturn(proof);assertThrows(ApiFailure.class,()->phoneService.confirm(id,sid,challenge,"invalid"));}
        org.mockito.Mockito.when(firebaseAdapter.verify("correct")).thenReturn(new PhoneProofVerifier.Proof("phone-dev","uid","+84900000002","phone",now));assertThrows(ApiFailure.class,()->phoneService.confirm(id,sid,challenge,"correct"));
        UUID other=create();var otherJwt=decoder.decode(login(other).tokens().accessToken());assertThrows(ApiFailure.class,()->phoneService.confirm(other,UUID.fromString(otherJwt.getClaimAsString("sid")),challenge,"correct"));
        assertFalse(service.profile(id).phoneVerified());
    }
    @Test void phoneProofCannotFinishAfterLogoutAndDoesNotAutoElevate(){
        UUID id=create();var jwt=decoder.decode(login(id).tokens().accessToken());UUID sid=UUID.fromString(jwt.getClaimAsString("sid"));String challenge=phoneService.challenge(id,sid,"+84900000003",PASSWORD);
        service.logout(id,sid);org.mockito.Mockito.when(firebaseAdapter.verify("signed")).thenReturn(new PhoneProofVerifier.Proof("phone-dev","uid","+84900000003","phone",Instant.now().getEpochSecond()));
        assertThrows(ApiFailure.class,()->phoneService.confirm(id,sid,challenge,"signed"));assertEquals("USER",service.profile(id).role());
        assertThrows(ApiFailure.class,()->service.registerContact("testrealnumber",null,"+84999999999",PASSWORD,"Not allowed"));
    }
    @Test void loginTransactionsCannotStarveDurableAttemptCounterPool()throws Exception{
        UUID id=create();String identifier=service.profile(id).username();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(8)){
            var start=new java.util.concurrent.CountDownLatch(1);var results=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<8;i++)results.add(executor.submit(()->{start.await();try{service.login(identifier,"wrong-password");return false;}catch(ApiFailure failure){return failure.status()==401;}}));
            start.countDown();for(var result:results)assertTrue(result.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(8,store.jdbc().queryForObject("SELECT sum(attempts)::int FROM auth.rate_limits WHERE bucket_key=?",Integer.class,AuthCrypto.hash("login:"+identifier)));
    }
    @Test void lifecycleOutboxIsTransactionalImmutableOrderedAndRecoversExpiredLease(){
        UUID id=create();
        assertEquals(1,store.jdbc().queryForObject("SELECT count(*) FROM auth.outbox_events WHERE aggregate_id=? AND event_type='user.created'",Integer.class,id));
        try{new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(store.jdbc().getDataSource())).execute(status->{store.jdbc().update("UPDATE auth.users SET display_name='rolled back' WHERE id=?",id);throw new IllegalStateException("rollback fixture");});}catch(IllegalStateException expected){}
        assertEquals(1,store.jdbc().queryForObject("SELECT count(*) FROM auth.outbox_events WHERE aggregate_id=?",Integer.class,id));
        service.updateProfile(id,"Updated");
        assertThrows(org.springframework.dao.DataAccessException.class,()->store.jdbc().execute("UPDATE auth.outbox_events SET envelope='{}'::jsonb"));
        var claimed=deliveryQueue.claim(DeliveryQueue.Kind.EVENT,100);var first=claimed.stream().filter(e->id.equals(e.get("aggregate_id"))).findFirst().orElseThrow();
        assertEquals("user.created",first.get("event_type"));UUID firstId=(UUID)first.get("id"),lease=(UUID)first.get("lease_id");
        assertTrue(deliveryQueue.claim(DeliveryQueue.Kind.EVENT,100).stream().noneMatch(e->id.equals(e.get("aggregate_id"))));
        provisioning.update("UPDATE auth.outbox_events SET lease_until=now()-interval '1 second' WHERE id=?",firstId);
        var recovered=deliveryQueue.claim(DeliveryQueue.Kind.EVENT,100).stream().filter(e->firstId.equals(e.get("id"))).findFirst().orElseThrow();UUID newLease=(UUID)recovered.get("lease_id");assertNotEquals(lease,newLease);
        deliveryQueue.complete(DeliveryQueue.Kind.EVENT,firstId,lease);assertNull(store.jdbc().queryForMap("SELECT published_at FROM auth.outbox_events WHERE id=?",firstId).get("published_at"));
        deliveryQueue.complete(DeliveryQueue.Kind.EVENT,firstId,newLease);
        var next=deliveryQueue.claim(DeliveryQueue.Kind.EVENT,100).stream().filter(e->id.equals(e.get("aggregate_id"))).findFirst().orElseThrow();assertEquals("user.updated",next.get("event_type"));
        assertFalse(first.get("envelope").toString().contains(service.profile(id).email()));
        assertFalse(first.get("envelope").toString().contains("password"));
    }
    @Test void realSmtpDeliveryRetryAndSecurityAlertsAreLeasedAndSecretFree()throws Exception{
        try(var mailpit=new org.testcontainers.containers.GenericContainer<>("axllent/mailpit:v1.27.8").withExposedPorts(1025,8025)){
            mailpit.start();UUID id=create();store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);String recipient=service.profile(id).email();
            var sender=new org.springframework.mail.javamail.JavaMailSenderImpl();sender.setHost(mailpit.getHost());sender.setPort(mailpit.getMappedPort(1025));sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout","1000");sender.getJavaMailProperties().setProperty("mail.smtp.timeout","1000");sender.getJavaMailProperties().setProperty("mail.smtp.writetimeout","1000");sender.getJavaMailProperties().setProperty("mail.smtp.localhost","auth.baotin.test");sender.getJavaMailProperties().setProperty("mail.host","auth.baotin.test");sender.getJavaMailProperties().setProperty("mail.from","security@example.test");sender.getJavaMailProperties().setProperty("mail.smtp.quitwait","false");
            var worker=new MailDelivery(deliveryQueue,keys,sender,"security@example.test",notices);
            for(int drain=0;drain<20;drain++)worker.deliver();
            service.forgot(recipient);service.forgot(recipient);
            mailpit.getDockerClient().pauseContainerCmd(mailpit.getContainerId()).exec();
            try{worker.deliver();}finally{mailpit.getDockerClient().unpauseContainerCmd(mailpit.getContainerId()).exec();}
            assertTrue(store.jdbc().queryForObject("SELECT count(*) FROM auth.mail_outbox WHERE sent_at IS NULL AND attempts>0",Integer.class)>0);
            provisioning.update("UPDATE auth.mail_outbox SET next_attempt_at=now() WHERE sent_at IS NULL");
            for(int drain=0;drain<20;drain++)worker.deliver();
            String tokenPayload=null;
            // Queue a fresh reset proof and inspect it only in the test, not logs/API output.
            provisioning.update("DELETE FROM auth.rate_limits WHERE bucket_key=?",AuthCrypto.hash("forgot:"+recipient));service.forgot(recipient);
            for(var row:store.jdbc().queryForList("SELECT encrypted_payload FROM auth.mail_outbox WHERE sent_at IS NULL ORDER BY created_at DESC")){
                String payload=new String(keys.decrypt((byte[])row.get("encrypted_payload")),java.nio.charset.StandardCharsets.UTF_8);
                if(payload.startsWith(recipient+"\n")){tokenPayload=payload;break;}
            }
            assertNotNull(tokenPayload);String reset=tokenPayload.substring(tokenPayload.lastIndexOf(": ")+2);service.resetPassword(reset,PASSWORD);
            for(int drain=0;drain<20;drain++)worker.deliver();
            var request=HttpRequest.newBuilder(URI.create("http://"+mailpit.getHost()+":"+mailpit.getMappedPort(8025)+"/api/v1/messages")).GET().build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
            var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());boolean alert=false;
            for(var message:json.get("messages")){
                if(message.get("Subject").asText().equals("BaoTin security alert")){
                    var detail=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://"+mailpit.getHost()+":"+mailpit.getMappedPort(8025)+"/api/v1/message/"+message.get("ID").asText())).GET().build(),HttpResponse.BodyHandlers.ofString());
                    var text=tools.jackson.databind.json.JsonMapper.builder().build().readTree(detail.body()).get("Text").asText();
                    if(text.contains("PASSWORD_RESET")){alert=true;assertFalse(text.contains(reset));assertFalse(text.contains(PASSWORD));}
                }
            }
            assertTrue(alert);assertEquals(0,store.jdbc().queryForObject("SELECT count(*) FROM auth.mail_outbox WHERE sent_at IS NOT NULL AND octet_length(encrypted_payload)>0",Integer.class));
            assertEquals(1,store.jdbc().queryForObject("SELECT count(*) FROM auth.security_notices WHERE user_id=? AND event_type='PASSWORD_RESET' AND handled_at IS NOT NULL",Integer.class,id));
        }
    }
    @Test void realKafkaPublisherUsesStableEventIdAndHonorsRetryAfterBrokerFailure()throws Exception{
        try(var kafka=new org.testcontainers.kafka.KafkaContainer("apache/kafka-native:3.8.0")){
            kafka.start();UUID id=create();String created=store.jdbc().queryForObject("SELECT id::text FROM auth.outbox_events WHERE aggregate_id=? AND event_type='user.created'",String.class,id);
            // Release leases held by earlier queue fixtures; ordering must still be honored.
            provisioning.update("UPDATE auth.outbox_events SET lease_until=now()-interval '1 second',next_attempt_at=now() WHERE published_at IS NULL");
            try(var publisher=new KafkaOutbox(deliveryQueue,kafka.getBootstrapServers(),"PLAINTEXT","","")){
                for(int i=0;i<40 && store.jdbc().queryForObject("SELECT count(*) FROM auth.outbox_events WHERE published_at IS NULL AND attempts<10",Integer.class)>0;i++)publisher.publish();
                assertNotNull(store.jdbc().queryForMap("SELECT published_at FROM auth.outbox_events WHERE id=?::uuid",created).get("published_at"));
                var props=new java.util.Properties();props.put("bootstrap.servers",kafka.getBootstrapServers());props.put("group.id","verify-"+UUID.randomUUID());props.put("auto.offset.reset","earliest");props.put("enable.auto.commit",false);props.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");props.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
                boolean received=false;
                try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(props)){
                    consumer.subscribe(List.of("baotin.auth.events.v1"));
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                    while(System.nanoTime()<deadline&&!received)for(var record:consumer.poll(java.time.Duration.ofMillis(500))){
                        var payload=tools.jackson.databind.json.JsonMapper.builder().build().readTree(record.value());
                        if(payload.get("eventId").asText().equals(created)){assertEquals(id.toString(),record.key());assertEquals("user.created",payload.get("eventType").asText());received=true;}
                    }
                }assertTrue(received);
                // Broker accepted the record, but a publisher crash before DB marking
                // leaves this exact event pending. Resend must preserve eventId/key.
                provisioning.update("UPDATE auth.outbox_events SET published_at=NULL,next_attempt_at=now() WHERE id=?::uuid",created);publisher.publish();
                props.put("group.id","duplicate-"+UUID.randomUUID());int duplicates=0;
                try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(props)){
                    consumer.subscribe(List.of("baotin.auth.events.v1"));long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                    while(System.nanoTime()<deadline&&duplicates<2)for(var record:consumer.poll(java.time.Duration.ofMillis(500))){
                        var payload=tools.jackson.databind.json.JsonMapper.builder().build().readTree(record.value());if(payload.get("eventId").asText().equals(created)){assertEquals(id.toString(),record.key());duplicates++;}
                    }
                }assertTrue(duplicates>=2);
                service.updateProfile(id,"Broker outage update");
                kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();try{publisher.publish();}finally{kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();}
                assertTrue(store.jdbc().queryForObject("SELECT count(*) FROM auth.outbox_events WHERE aggregate_id=? AND published_at IS NULL AND attempts>0",Integer.class,id)>0);
                provisioning.update("UPDATE auth.outbox_events SET next_attempt_at=now() WHERE aggregate_id=? AND published_at IS NULL",id);publisher.publish();
                assertEquals(0,store.jdbc().queryForObject("SELECT count(*) FROM auth.outbox_events WHERE aggregate_id=? AND published_at IS NULL",Integer.class,id));
            }
        }
    }
    @Test void actualCookieRefreshAndLogoutEnforceCsrfRotationAndOrigin()throws Exception{
        UUID id=create();String username=service.profile(id).username();var client=HttpClient.newHttpClient();String origin="https://localhost:9444";
        var login=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/web/login")).header("Origin",origin).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"identifier\":\""+username+"\",\"password\":\""+PASSWORD+"\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,login.statusCode());String cookie=login.headers().allValues("Set-Cookie").stream().filter(s->s.startsWith("__Host-baotin-refresh=")).findFirst().orElseThrow().split(";",2)[0];
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();String csrf=mapper.readTree(login.body()).get("csrfToken").asText();
        var missing=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/web/refresh")).header("Origin",origin).header("Cookie",cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,missing.statusCode());
        var evil=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/web/refresh")).header("Origin","https://evil.test").header("Cookie",cookie).header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,evil.statusCode());
        var valid=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/web/refresh")).header("Origin",origin).header("Cookie",cookie).header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,valid.statusCode());
        String nextCookie=valid.headers().allValues("Set-Cookie").stream().filter(s->s.startsWith("__Host-baotin-refresh=")).findFirst().orElseThrow().split(";",2)[0];var json=mapper.readTree(valid.body());String nextCsrf=json.get("csrfToken").asText(),access=json.get("accessToken").asText();assertNotEquals(csrf,nextCsrf);assertNotEquals(cookie,nextCookie);assertFalse(valid.body().contains("refreshToken"));
        var stale=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/web/logout")).header("Origin",origin).header("Cookie",nextCookie).header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,stale.statusCode());
        var logout=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/web/logout")).header("Origin",origin).header("Cookie",nextCookie).header("X-CSRF-TOKEN",nextCsrf).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(204,logout.statusCode());assertEquals(401,request("GET","/api/v1/auth/me",null,access).statusCode());
    }
    @Test void httpMutationPersistsGeneratedCorrelationInOutbox()throws Exception{
        String username="http"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        var response=request("POST","/api/v1/auth/register","{\"username\":\""+username+"\",\"email\":\""+username+"@example.test\",\"password\":\""+PASSWORD+"\",\"displayName\":\"Correlation\"}",null);
        assertEquals(201,response.statusCode());String correlation=response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertEquals(correlation,store.jdbc().queryForObject("SELECT e.envelope->>'correlationId' FROM auth.outbox_events e JOIN auth.users u ON u.id=e.aggregate_id WHERE u.username=? AND e.event_type='user.created'",String.class,username));
    }
    @Test void providerWithoutEmailHasPkceBoundContactOnboardingWithoutAutoLink(){
        String verifier=AuthCrypto.token();var start=oauth.start("FACEBOOK","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),null,null);
        String providerCode=OAuthFixture.code(start,UUID.randomUUID().toString(),null,null,"google-client","facebook-client");String ticket=OAuthFixture.ticket(oauth.callback("facebook",OAuthFixture.state(start),providerCode,null));
        var pending=oauth.exchange(ticket,verifier,"NATIVE");assertTrue(pending.onboardingRequired());assertNull(pending.login());
        String contact=UUID.randomUUID()+"@example.test";var created=oauth.exchangeContact(ticket,verifier,"NATIVE",contact);assertNotNull(created.login().tokens());
        UUID id=UUID.fromString(decoder.decode(created.login().tokens().accessToken()).getSubject());assertEquals(contact,service.profile(id).email());assertFalse(service.profile(id).emailVerified());assertEquals("USER",service.profile(id).role());
        assertThrows(ApiFailure.class,()->oauth.exchangeContact(ticket,verifier,"NATIVE",contact));
        UUID existing=create();var collision=oauth.start("FACEBOOK","NATIVE","baotin://auth/callback",OAuthService.challenge(verifier),null,null);
        String collisionCode=OAuthFixture.code(collision,UUID.randomUUID().toString(),null,null,"google-client","facebook-client");String collisionTicket=OAuthFixture.ticket(oauth.callback("facebook",OAuthFixture.state(collision),collisionCode,null));
        assertEquals("ACCOUNT_LINK_REQUIRED",assertThrows(ApiFailure.class,()->oauth.exchangeContact(collisionTicket,verifier,"NATIVE",service.profile(existing).email())).code());
    }
    @Test void recoveryRegenerationRequiresActualTotpInvalidatesOldCodesAndSessions(){
        UUID id=create();store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);service.enroll(id,PASSWORD);
        byte[] secret=keys.decrypt((byte[])store.jdbc().queryForMap("SELECT encrypted_secret FROM auth.mfa_enrollments WHERE user_id=?",id).get("encrypted_secret"));long step=Instant.now().getEpochSecond()/30;
        var old=service.confirmEnrollment(id,AuthCrypto.totp(secret,step-1));var challenge=login(id);var session=service.completeChallenge(challenge.challenge(),old.getFirst());
        assertThrows(ApiFailure.class,()->service.regenerateRecoveryCodes(id,PASSWORD,old.get(1)));
        var replacement=service.regenerateRecoveryCodes(id,PASSWORD,AuthCrypto.totp(secret,step));assertEquals(10,replacement.size());
        assertEquals(0,store.jdbc().queryForObject("SELECT count(*) FROM auth.refresh_sessions WHERE user_id=? AND revoked_at IS NULL",Integer.class,id));
        var next=login(id);assertThrows(ApiFailure.class,()->service.completeChallenge(next.challenge(),old.get(1)));assertNotNull(service.completeChallenge(next.challenge(),replacement.getFirst()));
    }
    @Test void exhaustedDeliveryRequeueRequiresDedicatedOperatorAndLeavesEnvelopeImmutable(){
        UUID id=create();UUID event=store.jdbc().queryForObject("SELECT id FROM auth.outbox_events WHERE aggregate_id=? AND event_type='user.created'",UUID.class,id);String before=store.jdbc().queryForObject("SELECT envelope::text FROM auth.outbox_events WHERE id=?",String.class,event);
        provisioning.update("UPDATE auth.outbox_events SET attempts=10 WHERE id=?",event);
        assertThrows(org.springframework.dao.DataAccessException.class,()->store.jdbc().queryForObject("SELECT auth.operator_retry_delivery('EVENT',?,'runtime must not retry exhausted record')",Object.class,event));
        var operator=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),"baotin_auth_operator",OPERATOR_PASSWORD));
        operator.queryForObject("SELECT auth.operator_retry_delivery('EVENT',?,'broker restored approved replay')",Object.class,event);
        assertEquals(0,store.jdbc().queryForObject("SELECT attempts FROM auth.outbox_events WHERE id=?",Integer.class,event));assertEquals(before,store.jdbc().queryForObject("SELECT envelope::text FROM auth.outbox_events WHERE id=?",String.class,event));
        assertThrows(org.springframework.dao.DataAccessException.class,()->operator.queryForObject("SELECT auth.operator_retry_delivery('EVENT',?,'not exhausted cannot be retried')",Object.class,event));
        assertEquals(1,provisioning.queryForObject("SELECT count(*) FROM auth.audit_events WHERE user_id=? AND event_type='OPERATOR_DELIVERY_RETRY_EVENT'",Integer.class,id));
    }
    private UUID emailSid(AuthService.Tokens t){return UUID.fromString(decoder.decode(t.accessToken()).getClaimAsString("sid"));}
    private String emailCode(UUID id,String purpose){
        String address=service.profile(id).email();
        for(var row:store.jdbc().queryForList("SELECT encrypted_payload FROM auth.mail_outbox WHERE encrypted_payload IS NOT NULL ORDER BY created_at DESC")){
            String payload=new String(keys.decrypt((byte[])row.get("encrypted_payload")),java.nio.charset.StandardCharsets.UTF_8);
            String prefix=address+"\nBaoTin MFA_"+purpose+"\nYour single-use authentication code: ";
            if(payload.startsWith(prefix))return payload.substring(prefix.length(),prefix.length()+6);
        }throw new AssertionError("Own fixture mail proof missing");
    }
    private UUID emailUser(){UUID id=create();store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);var first=login(id).tokens();var p=service.enrollEmailMfa(id,emailSid(first),PASSWORD);service.confirmEmailMfa(id,emailSid(first),p.challenge(),emailCode(id,"ENROLL"));return id;}
    @Test void emailEnrollmentRequiresOwnerPasswordVerifiedEmailAndNeverAcceptsTotpFallback()throws Exception{
        UUID id=create();var first=login(id).tokens();UUID sid=emailSid(first);
        assertThrows(ApiFailure.class,()->service.enrollEmailMfa(id,sid,PASSWORD));
        store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);
        assertThrows(ApiFailure.class,()->service.enrollEmailMfa(id,sid,"wrong"));
        var pending=service.enrollEmailMfa(id,sid,PASSWORD);String code=emailCode(id,"ENROLL");
        UUID other=create();var otherToken=login(other).tokens();
        assertThrows(ApiFailure.class,()->service.confirmEmailMfa(other,emailSid(otherToken),pending.challenge(),code));
        assertThrows(ApiFailure.class,()->service.completeChallenge(pending.challenge(),code));
        service.confirmEmailMfa(id,sid,pending.challenge(),code);
        assertEquals("EMAIL",service.profile(id).mfaMethod());assertTrue(service.profile(id).mfaEnabled());
        assertEquals(401,request("GET","/api/v1/auth/me",null,first.accessToken()).statusCode());
        assertThrows(ApiFailure.class,()->service.confirmEmailMfa(id,sid,pending.challenge(),code));
        var challenge=login(id);assertNull(challenge.tokens());assertEquals("EMAIL",challenge.mfaMethod());
        String loginCode=emailCode(id,"LOGIN");
        assertThrows(ApiFailure.class,()->service.completeWebChallenge(challenge.challenge(),loginCode));
        var token=service.completeChallenge(challenge.challenge(),loginCode);
        assertEquals(200,request("GET","/api/v1/auth/me",null,token.accessToken()).statusCode());
        assertEquals(403,request("GET","/api/v1/auth/admin/users",null,token.accessToken()).statusCode());
        assertThrows(ApiFailure.class,()->admin.stepUp(id,emailSid(token),loginCode));
        assertThrows(ApiFailure.class,()->service.completeChallenge(challenge.challenge(),loginCode));
    }
    @Test void emailProofExpiryVersionAttemptExhaustionAndResetPreserveMfa(){
        UUID id=emailUser();var c=login(id);String correct=emailCode(id,"LOGIN");
        String bad=correct.equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++)assertThrows(ApiFailure.class,()->service.completeChallenge(c.challenge(),bad));
        assertEquals(5,store.jdbc().queryForObject("SELECT attempts FROM auth.email_mfa_challenges WHERE token_hash=?",Integer.class,AuthCrypto.hash(c.challenge())));
        assertThrows(ApiFailure.class,()->service.completeChallenge(c.challenge(),correct));
        var expiry=login(id);String ec=emailCode(id,"LOGIN");store.jdbc().update("UPDATE auth.email_mfa_challenges SET expires_at=now()-interval '1 second' WHERE token_hash=?",AuthCrypto.hash(expiry.challenge()));
        assertThrows(ApiFailure.class,()->service.completeChallenge(expiry.challenge(),ec));
        var stale=login(id);String sc=emailCode(id,"LOGIN");service.logoutAll(id);
        assertThrows(ApiFailure.class,()->service.completeChallenge(stale.challenge(),sc));
        assertEquals("EMAIL",service.profile(id).mfaMethod());
    }
    @Test void emailResendHasCooldownReplacesCodeAndDoesNotExtendExpiry(){
        UUID id=emailUser();var c=login(id);String old=emailCode(id,"LOGIN");
        var before=store.jdbc().queryForMap("SELECT expires_at FROM auth.email_mfa_challenges WHERE token_hash=?",AuthCrypto.hash(c.challenge()));
        assertThrows(ApiFailure.class,()->service.resendEmailMfa(c.challenge(),"NATIVE"));
        store.jdbc().update("UPDATE auth.email_mfa_challenges SET last_sent_at=now()-interval '31 seconds' WHERE token_hash=?",AuthCrypto.hash(c.challenge()));
        service.resendEmailMfa(c.challenge(),"NATIVE");String next=emailCode(id,"LOGIN");
        assertEquals(before.get("expires_at"),store.jdbc().queryForMap("SELECT expires_at FROM auth.email_mfa_challenges WHERE token_hash=?",AuthCrypto.hash(c.challenge())).get("expires_at"));
        assertNotEquals(old,next);assertThrows(ApiFailure.class,()->service.completeChallenge(c.challenge(),old));
        assertNotNull(service.completeChallenge(c.challenge(),next).accessToken());
    }
    @Test void emailActionsArePurposeSessionBoundAndDisableRequiresPassword()throws Exception{
        UUID id=emailUser();var c=login(id);var t=service.completeChallenge(c.challenge(),emailCode(id,"LOGIN"));UUID sid=emailSid(t);
        var action=service.emailActionChallenge(id,sid,"CHANGE_PASSWORD");String code=emailCode(id,"CHANGE_PASSWORD");
        assertThrows(ApiFailure.class,()->service.disableMfa(id,sid,PASSWORD,action.challenge()+"."+code));
        var c2=login(id);var t2=service.completeChallenge(c2.challenge(),emailCode(id,"LOGIN"));
        assertThrows(ApiFailure.class,()->service.changePassword(id,emailSid(t2),PASSWORD,PASSWORD+" changed",action.challenge()+"."+code));
        service.changePassword(id,sid,PASSWORD,PASSWORD+" changed",action.challenge()+"."+code);
        assertEquals(401,request("GET","/api/v1/auth/me",null,t.accessToken()).statusCode());
        var newer=service.login(service.profile(id).username(),PASSWORD+" changed");assertTrue(newer.mfaRequired());
        var nt=service.completeChallenge(newer.challenge(),emailCode(id,"LOGIN"));
        var disable=service.emailActionChallenge(id,emailSid(nt),"DISABLE_MFA");String dc=emailCode(id,"DISABLE_MFA");
        assertThrows(ApiFailure.class,()->service.disableMfa(id,emailSid(nt),"wrong",disable.challenge()+"."+dc));
        service.disableMfa(id,emailSid(nt),PASSWORD+" changed",disable.challenge()+"."+dc);
        assertFalse(service.profile(id).mfaEnabled());
    }
    @Test void emailUpgradeToTotpRevokesEmailProofAndPrivilegePromotionRequiresTotp()throws Exception{
        UUID id=emailUser();var c=login(id);var t=service.completeChallenge(c.challenge(),emailCode(id,"LOGIN"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->provisioning.update("UPDATE auth.users SET role='ADMIN' WHERE id=?",id));
        var pending=service.enroll(id,PASSWORD);
        byte[] secret=keys.decrypt((byte[])store.jdbc().queryForMap("SELECT encrypted_secret FROM auth.mfa_enrollments WHERE user_id=?",id).get("encrypted_secret"));
        service.confirmEnrollment(id,AuthCrypto.totp(secret,Instant.now().getEpochSecond()/30));
        assertEquals("TOTP",service.profile(id).mfaMethod());
        assertEquals(401,request("GET","/api/v1/auth/me",null,t.accessToken()).statusCode());
        assertThrows(ApiFailure.class,()->service.enrollEmailMfa(id,emailSid(t),PASSWORD));
        assertThrows(ApiFailure.class,()->service.resendEmailMfa(c.challenge(),"NATIVE"));
    }
    @Test void emailLoginConcurrentProofIssuesOnlyOneSession()throws Exception{
        UUID id=emailUser();var c=login(id);String code=emailCode(id,"LOGIN");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var calls=List.<java.util.concurrent.Callable<Boolean>>of(()->{try{service.completeChallenge(c.challenge(),code);return true;}catch(ApiFailure e){return false;}},()->{try{service.completeChallenge(c.challenge(),code);return true;}catch(ApiFailure e){return false;}});
            int accepted=0;for(var f:pool.invokeAll(calls))if(f.get())accepted++;assertEquals(1,accepted);
        }finally{pool.shutdownNow();}
    }
    @Test void emailFederatedAndWebLoginStillRequireTheirOwnFactorTransport()throws Exception{
        UUID id=emailUser();store.jdbc().update("INSERT INTO auth.oauth_accounts(id,user_id,provider,provider_subject) VALUES (?,?,?,?)",UUID.randomUUID(),id,"FACEBOOK","email-mfa-"+id);
        var c=service.providerLogin("FACEBOOK","email-mfa-"+id,null,false,"Name","WEB");assertEquals("EMAIL",c.mfaMethod());
        String code=emailCode(id,"LOGIN");assertThrows(ApiFailure.class,()->service.completeChallenge(c.challenge(),code));
        var t=service.completeWebChallenge(c.challenge(),code);assertNotNull(t.accessToken());
        assertThrows(ApiFailure.class,()->service.refresh(t.refreshToken()));
        String csrf=service.bindCsrf(t.refreshToken());assertNotNull(service.webRefresh(t.refreshToken(),csrf));
    }

    @Test void emailEnrollmentSnapshotExpiryAndStepUpCannotBeReusedAcrossPurposes()throws Exception{
        UUID id=create();store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);var t=login(id).tokens();UUID sid=emailSid(t);
        var enroll=service.enrollEmailMfa(id,sid,PASSWORD);String proof=emailCode(id,"ENROLL");
        provisioning.update("UPDATE auth.email_mfa_challenges SET expires_at=now()-interval '1 second' WHERE token_hash=?",AuthCrypto.hash(enroll.challenge()));
        assertThrows(ApiFailure.class,()->service.confirmEmailMfa(id,sid,enroll.challenge(),proof));
        var next=service.enrollEmailMfa(id,sid,PASSWORD);String nc=emailCode(id,"ENROLL");
        provisioning.update("UPDATE auth.users SET email=? WHERE id=?","changed"+id+"@example.test",id);
        assertThrows(ApiFailure.class,()->service.confirmEmailMfa(id,sid,next.challenge(),nc));
        assertEquals("NONE",service.profile(id).mfaMethod());
        UUID e=emailUser();var c=login(e);var et=service.completeChallenge(c.challenge(),emailCode(e,"LOGIN"));UUID es=emailSid(et);
        var step=service.emailActionChallenge(e,es,"STEP_UP");String sc=emailCode(e,"STEP_UP");service.emailStepUp(e,es,step.challenge(),sc);
        assertNotNull(store.jdbc().queryForMap("SELECT step_up_at FROM auth.refresh_sessions WHERE id=?",es).get("step_up_at"));
        assertThrows(ApiFailure.class,()->service.emailStepUp(e,es,step.challenge(),sc));
        assertThrows(ApiFailure.class,()->service.changePassword(e,es,PASSWORD,PASSWORD+" fresh",step.challenge()+"."+sc));
        assertEquals(403,request("GET","/api/v1/auth/admin/users",null,et.accessToken()).statusCode());
    }
    @Test void emailResetPasswordAndStoredProofAreBoundAndDoNotDisableSecondFactor(){
        UUID id=emailUser();var c=login(id);String code=emailCode(id,"LOGIN");
        assertEquals(AuthCrypto.hash(c.challenge()+":LOGIN:"+code),store.jdbc().queryForObject("SELECT code_hash FROM auth.email_mfa_challenges WHERE token_hash=?",String.class,AuthCrypto.hash(c.challenge())));
        service.forgot(service.profile(id).email());String address=service.profile(id).email(),reset=null;
        for(var row:store.jdbc().queryForList("SELECT encrypted_payload FROM auth.mail_outbox WHERE encrypted_payload IS NOT NULL ORDER BY created_at DESC")){
            String payload=new String(keys.decrypt((byte[])row.get("encrypted_payload")),java.nio.charset.StandardCharsets.UTF_8);
            if(payload.startsWith(address+"\nBaoTin RESET_PASSWORD\n")){reset=payload.substring(payload.lastIndexOf(": ")+2);break;}
        }assertNotNull(reset);service.resetPassword(reset,PASSWORD+" reset");
        assertThrows(ApiFailure.class,()->service.completeChallenge(c.challenge(),code));
        assertEquals("EMAIL",service.profile(id).mfaMethod());assertTrue(service.login(service.profile(id).username(),PASSWORD+" reset").mfaRequired());
    }
    @Test void readerTwoBridgeSupportsLegacySchemaAndEmailEndpointsFailClosed()throws Exception{
        try(var legacy=new PostgreSQLContainer<>("postgres:17-alpine")){
            legacy.start();try(var connection=java.sql.DriverManager.getConnection(legacy.getJdbcUrl(),legacy.getUsername(),legacy.getPassword());var st=connection.createStatement()){
                st.execute(resource("db/provision/before-migrate.sql"));st.execute("ALTER ROLE baotin_auth PASSWORD '"+RUNTIME_PASSWORD+"'");st.execute("ALTER ROLE baotin_auth_migrator PASSWORD '"+MIGRATION_PASSWORD+"'");
            }
            org.flywaydb.core.Flyway.configure().dataSource(legacy.getJdbcUrl(),"baotin_auth_migrator",MIGRATION_PASSWORD).schemas("auth").defaultSchema("auth").createSchemas(false).target("9").load().migrate();
            try(var connection=java.sql.DriverManager.getConnection(legacy.getJdbcUrl(),legacy.getUsername(),legacy.getPassword());var st=connection.createStatement()){st.execute(resource("db/provision/after-migrate.sql"));}
            var db=new IdentityStore(new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(legacy.getJdbcUrl(),"baotin_auth",RUNTIME_PASSWORD)));
            var encoderPassword=new BoundedPasswordEncoder(new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,1,19456,2),2);
            var clock=java.time.Clock.systemUTC();
            try(var limit=new AttemptLimits(legacy.getJdbcUrl(),"baotin_auth",RUNTIME_PASSWORD,clock)){
                var email=new EmailMfa(db,keys,clock,limit);
                var app=new AuthService(db,encoderPassword,keys,configuredJwt,encoder,clock,limit,new PhonePolicy(new org.springframework.mock.env.MockEnvironment()),email);
                UUID id=UUID.randomUUID();db.jdbc().update("INSERT INTO auth.users(id,username,email,password_hash,display_name) VALUES (?,?,?,?,?)",id,"legacyuser","legacy@example.test",encoderPassword.encode(PASSWORD),"Legacy");db.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);
                assertFalse(db.byId(id).orElseThrow().emailMfaEnabled());assertFalse(email.available());
                var t=app.login("legacyuser",PASSWORD).tokens();assertNotNull(t.accessToken());
                var failure=assertThrows(ApiFailure.class,()->app.enrollEmailMfa(id,emailSid(t),PASSWORD));assertEquals(503,failure.status());
                assertEquals("9",com.baotin.auth.operator.RuntimeDatabaseState.read(legacy.getJdbcUrl(),"baotin_auth",RUNTIME_PASSWORD).get("schemaRevision"));
            }
        }
    }

    private String smtpFixtureProof(org.testcontainers.containers.GenericContainer<?> sink,MailDelivery worker,String recipient,String subject)throws Exception{
        for(int i=0;i<20;i++)worker.deliver();
        String base="http://"+sink.getHost()+":"+sink.getMappedPort(8025);
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/api/v1/messages")).GET().build(),HttpResponse.BodyHandlers.ofString());
        var messages=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body()).get("messages");
        for(var message:messages){if(subject.equals(message.get("Subject").asText()) && message.get("To").toString().contains(recipient)){
            var detail=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/api/v1/message/"+message.get("ID").asText())).GET().build(),HttpResponse.BodyHandlers.ofString());
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(detail.body()).get("Text").asText();
        }}throw new AssertionError("Own SMTP fixture message missing");
    }
    @Test void actualSmtpInboxEmailOtpCompletesEnrollmentAndHttpLoginWithoutTokenLeak()throws Exception{
        try(var sink=new org.testcontainers.containers.GenericContainer<>("axllent/mailpit:v1.27.8").withExposedPorts(1025,8025)){
            sink.start();var sender=new org.springframework.mail.javamail.JavaMailSenderImpl();sender.setHost(sink.getHost());sender.setPort(sink.getMappedPort(1025));
            for(String key:List.of("mail.smtp.connectiontimeout","mail.smtp.timeout","mail.smtp.writetimeout"))sender.getJavaMailProperties().setProperty(key,"1000");
            sender.getJavaMailProperties().setProperty("mail.smtp.localhost","auth.baotin.test");sender.getJavaMailProperties().setProperty("mail.host","auth.baotin.test");sender.getJavaMailProperties().setProperty("mail.from","security@example.test");sender.getJavaMailProperties().setProperty("mail.smtp.quitwait","false");
            var worker=new MailDelivery(deliveryQueue,keys,sender,"security@example.test",notices);
            UUID id=create();String recipient=service.profile(id).email();
            String verify=smtpFixtureProof(sink,worker,recipient,"BaoTin VERIFY_EMAIL");service.verifyEmail(verify.substring(verify.lastIndexOf(": ")+2).trim());
            var t=login(id).tokens();UUID sid=emailSid(t);var pending=service.enrollEmailMfa(id,sid,PASSWORD);
            String enrollment=smtpFixtureProof(sink,worker,recipient,"BaoTin MFA_ENROLL");
            var matcher=java.util.regex.Pattern.compile("code: ([0-9]{6})").matcher(enrollment);assertTrue(matcher.find());
            service.confirmEmailMfa(id,sid,pending.challenge(),matcher.group(1));
            var response=request("POST","/api/v1/auth/login","{\"identifier\":\""+service.profile(id).username()+"\",\"password\":\""+PASSWORD+"\"}",null);
            assertEquals(200,response.statusCode());var data=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
            assertEquals("EMAIL",data.get("mfaMethod").asText());assertTrue(data.get("tokens").isNull());String challenge=data.get("challenge").asText();
            String mail=smtpFixtureProof(sink,worker,recipient,"BaoTin MFA_LOGIN");matcher=java.util.regex.Pattern.compile("code: ([0-9]{6})").matcher(mail);assertTrue(matcher.find());
            assertFalse(response.body().contains(matcher.group(1)));
            var finished=request("POST","/api/v1/auth/mfa/challenges","{\"challenge\":\""+challenge+"\",\"code\":\""+matcher.group(1)+"\"}",null);
            assertEquals(200,finished.statusCode());var tokens=tools.jackson.databind.json.JsonMapper.builder().build().readTree(finished.body());
            assertEquals(200,request("GET","/api/v1/auth/me",null,tokens.get("accessToken").asText()).statusCode());
            assertThrows(ApiFailure.class,()->service.completeChallenge(challenge,"not-a-proof"));
        }
    }

}
