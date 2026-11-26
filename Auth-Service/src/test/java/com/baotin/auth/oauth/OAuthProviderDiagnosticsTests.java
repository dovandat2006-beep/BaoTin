package com.baotin.auth.oauth;

import com.baotin.auth.api.ApiFailure;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP failure diagnostics; response bodies and credential values stay private. */
@ExtendWith(OutputCaptureExtension.class)
class OAuthProviderDiagnosticsTests {
    private static final String PRIVATE_CODE="callback-sentinel";
    private static final String PRIVATE_ACCESS="provider-access-sentinel";
    private static final String PRIVATE_SECRET="fixture-secret";
    private final AtomicReference<String> failure=new AtomicReference<>("");
    private HttpServer server;
    private OAuthProviders providers;

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/token",exchange->{
            exchange.getRequestBody().readAllBytes();
            if(failure.get().equals("TOKEN_EXCHANGE"))reply(exchange,400,error(100,36008));
            else if(failure.get().equals("NON_NUMERIC"))reply(exchange,400,error(PRIVATE_ACCESS,PRIVATE_CODE));
            else if(failure.get().equals("MALFORMED")){
                byte[] data=("not-json:"+PRIVATE_SECRET).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
            }else reply(exchange,200,Map.of("access_token",PRIVATE_ACCESS));
        });
        server.createContext("/graph/debug_token",exchange->{
            if(failure.get().equals("TOKEN_DEBUG"))reply(exchange,400,error(190,458));
            else if(failure.get().equals("SCRIPT_BODY") || failure.get().equals("TRAILING_SCRIPT")){
                String json=failure.get().equals("TRAILING_SCRIPT")?"{\"data\":{\"is_valid\":true}};":"";
                byte[] data=(json+"window.alert('not JSON');").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/javascript; charset=UTF-8");
                exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
            }
            else reply(exchange,200,Map.of("data",Map.of("is_valid",true,"app_id","fixture-app",
                "user_id","fixture-subject","expires_at",Instant.now().plusSeconds(300).getEpochSecond())));
        });
        server.createContext("/graph/me",exchange->{
            if(failure.get().equals("PROFILE"))reply(exchange,403,error(10,0));
            else reply(exchange,200,Map.of("id","fixture-subject","name","Test User","email","user@example.test"));
        });
        server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        var env=new MockEnvironment().withProperty("auth.oauth.facebook.client-id","fixture-app")
            .withProperty("auth.oauth.facebook.client-secret",PRIVATE_SECRET)
            .withProperty("auth.oauth.facebook.callback-uri","https://gateway.example.test/callback")
            .withProperty("auth.oauth.facebook.token-uri",base+"/token")
            .withProperty("auth.oauth.facebook.graph-uri",base+"/graph");
        env.setActiveProfiles("test");providers=new OAuthProviders(env);
    }
    @AfterEach void stop(){server.stop(0);}
    private Map<String,Object> error(Object code,Object subcode){
        return Map.of("error",Map.of("code",code,"error_subcode",subcode,"message",
            PRIVATE_SECRET+" "+PRIVATE_CODE+" "+PRIVATE_ACCESS+" user@example.test"));
    }
    private void reply(com.sun.net.httpserver.HttpExchange exchange,int status,Object value) throws java.io.IOException {
        byte[] data=JsonMapper.builder().build().writeValueAsBytes(value);
        String media=failure.get().equals("LEGACY_MEDIA") && exchange.getRequestURI().getPath().startsWith("/graph/")
            ?"text/javascript; charset=UTF-8":"application/json";
        exchange.getResponseHeaders().set("Content-Type",media);
        exchange.sendResponseHeaders(status,data.length);exchange.getResponseBody().write(data);exchange.close();
    }
    private ApiFailure exchangeFailure(String stage){
        failure.set(stage);
        var result=assertThrows(ApiFailure.class,()->providers.exchange(providers.provider("FACEBOOK"),PRIVATE_CODE,"verifier","nonce"));
        assertEquals(401,result.status());assertEquals("OAUTH_PROOF_INVALID",result.code());
        assertEquals("Provider response could not be verified",result.getMessage());return result;
    }
    private void privateValuesAbsent(CapturedOutput output){
        String log=output.getAll();
        for(String value:new String[]{PRIVATE_SECRET,PRIVATE_CODE,PRIVATE_ACCESS,"user@example.test","input_token=","appsecret_proof="})
            assertFalse(log.contains(value),"Private provider/request data reached the log");
    }
    @Test void tokenFailureUsesOnlyStageAndNumericError(CapturedOutput output){
        exchangeFailure("TOKEN_EXCHANGE");
        assertTrue(output.getAll().contains("provider=FACEBOOK stage=TOKEN_EXCHANGE httpStatus=400 errorCode=100 errorSubcode=36008"));
        privateValuesAbsent(output);
    }
    @Test void debugFailureIsDistinguishedWithoutLoggingTokenUri(CapturedOutput output){
        exchangeFailure("TOKEN_DEBUG");
        assertTrue(output.getAll().contains("stage=TOKEN_DEBUG httpStatus=400 errorCode=190 errorSubcode=458"));
        privateValuesAbsent(output);
    }
    @Test void profileFailureIsDistinguishedWithoutLoggingProfile(CapturedOutput output){
        exchangeFailure("PROFILE");
        assertTrue(output.getAll().contains("stage=PROFILE httpStatus=403 errorCode=10 errorSubcode=0"));
        privateValuesAbsent(output);
    }
    @Test void malformedPayloadRemainsGenericAndPrivate(CapturedOutput output){
        exchangeFailure("MALFORMED");
        assertTrue(output.getAll().contains("stage=TOKEN_EXCHANGE httpStatus=0 errorCode=0 errorSubcode=0"));
        privateValuesAbsent(output);
    }
    @Test void attackerControlledErrorFieldsAreNotLogged(CapturedOutput output){
        exchangeFailure("NON_NUMERIC");
        assertTrue(output.getAll().contains("httpStatus=400 errorCode=0 errorSubcode=0"));
        privateValuesAbsent(output);
    }
    @Test void validIdentityStillBindsAppAndSubject(CapturedOutput output){
        var identity=providers.exchange(providers.provider("FACEBOOK"),PRIVATE_CODE,"verifier","nonce");
        assertEquals("fixture-subject",identity.subject());assertFalse(identity.emailVerified());
        assertFalse(output.getAll().contains("OAUTH_PROVIDER_FAILURE"));privateValuesAbsent(output);
    }
    @Test void acceptsLegacyFacebookJsonMediaWithoutChangingIdentityProof(CapturedOutput output){
        failure.set("LEGACY_MEDIA");
        var identity=providers.exchange(providers.provider("FACEBOOK"),PRIVATE_CODE,"verifier","nonce");
        assertEquals("fixture-subject",identity.subject());assertEquals("user@example.test",identity.email());
        assertFalse(identity.emailVerified());assertFalse(output.getAll().contains("OAUTH_PROVIDER_FAILURE"));
    }
    @Test void javascriptMediaDoesNotAuthorizeAJavascriptBody(CapturedOutput output){
        exchangeFailure("SCRIPT_BODY");privateValuesAbsent(output);
    }
    @Test void legacyMediaRejectsJsonWithTrailingJavascript(CapturedOutput output){
        exchangeFailure("TRAILING_SCRIPT");privateValuesAbsent(output);
    }
}
