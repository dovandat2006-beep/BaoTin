package com.baotin.auth;

import com.baotin.auth.oauth.OAuthService;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/OIDC signature fixture, never a provider identity stub. */
final class OAuthFixture {
    static final HttpServer SERVER;
    static final RSAKey KEY;
    static final JsonMapper JSON=JsonMapper.builder().build();
    record Code(String nonce,String challenge,String subject,String email,String audience,String app){}
    static final Map<String,Code> CODES=new java.util.concurrent.ConcurrentHashMap<>();
    static {
        try{
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
            KEY=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).keyID("provider-key").build();
            SERVER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            SERVER.createContext("/jwks",e->send(e,200,new JWKSet(KEY.toPublicJWK()).toJSONObject()));
            SERVER.createContext("/google/token",e->{
                try{
                    var form=params(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));var c=CODES.get(form.get("code"));
                    if(c==null || !"google-secret".equals(form.get("client_secret")) || !"google-client".equals(form.get("client_id")) || !c.challenge().equals(OAuthService.challenge(form.get("code_verifier")))){send(e,400,Map.of("error","invalid_grant"));return;}
                    Instant now=Instant.now();var claims=new JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(c.audience()).subject(c.subject()).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).claim("nonce",c.nonce()).claim("email",c.email()).claim("email_verified",true).claim("name","Google Test").build();
                    var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("provider-key").build(),claims);jwt.sign(new RSASSASigner(KEY.toRSAPrivateKey()));send(e,200,Map.of("id_token",jwt.serialize()));
                }catch(Exception ex){send(e,400,Map.of("error","invalid_grant"));}
            });
            SERVER.createContext("/facebook/token",e->{
                var form=params(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));var c=CODES.get(form.get("code"));
                if(c==null || !"facebook-secret".equals(form.get("client_secret"))){send(e,400,Map.of("error","invalid_grant"));return;}
                send(e,200,Map.of("access_token",form.get("code")));
            });
            SERVER.createContext("/graph/debug_token",e->{
                var c=CODES.get(params(e.getRequestURI().getRawQuery()).get("input_token"));
                if(c==null){send(e,200,Map.of("data",Map.of("is_valid",false)));return;}
                send(e,200,Map.of("data",Map.of("is_valid",true,"app_id",c.app(),"user_id",c.subject(),"expires_at",Instant.now().plusSeconds(300).getEpochSecond())));
            });
            SERVER.createContext("/graph/me",e->{
                String token=e.getRequestHeaders().getFirst("Authorization");var c=token==null?null:CODES.get(token.substring(7));
                if(c==null){send(e,401,Map.of("error","invalid_token"));return;}
                var profile=new HashMap<String,Object>();profile.put("id",c.subject());profile.put("name","Facebook Test");if(c.email()!=null)profile.put("email",c.email());send(e,200,profile);
            });SERVER.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static String base(){return "http://127.0.0.1:"+SERVER.getAddress().getPort();}
    static Map<String,String> params(String query){var out=new HashMap<String,String>();if(query==null)return out;for(String part:query.split("&")){String[] pair=part.split("=",2);out.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}return out;}
    static String code(OAuthService.Start start,String subject,String email,String nonce,String audience,String app){
        var p=params(URI.create(start.authorizationUrl()).getRawQuery());String code=UUID.randomUUID().toString();
        CODES.put(code,new Code(nonce==null?p.get("nonce"):nonce,p.get("code_challenge"),subject,email,audience,app));return code;
    }
    static String state(OAuthService.Start start){return params(URI.create(start.authorizationUrl()).getRawQuery()).get("state");}
    static String ticket(String redirect){return params(URI.create(redirect).getRawQuery()).get("code");}
    static void send(com.sun.net.httpserver.HttpExchange e,int status,Object value)throws java.io.IOException{
        byte[] body=JSON.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);e.close();
    }
}
