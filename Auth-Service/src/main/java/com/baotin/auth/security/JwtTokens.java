package com.baotin.auth.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class JwtTokens {
    private final String issuer;
    private final String audience;
    private final SigningKeys keys;
    public JwtTokens(@Value("${auth.issuer}") String issuer,@Value("${auth.audience}") String audience,SigningKeys keys){
        this.issuer=issuer;this.audience=audience;this.keys=keys;
    }
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean JwtEncoder encoder(){return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.rsa())));}
    @Bean JwtDecoder decoder(Clock clock) throws Exception {
        var processor=new com.nimbusds.jwt.proc.DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt")));
        processor.setJWSKeySelector(new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(com.nimbusds.jose.JWSAlgorithm.RS256,new ImmutableJWKSet<>(keys.publicSet())));
        var decoder=new NimbusJwtDecoder(processor);
        var time=new JwtTimestampValidator(Duration.ofSeconds(30));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(time,new JwtIssuerValidator(issuer),jwt->{
            boolean valid=jwt.getAudience().contains(audience) && keys.knownKid(jwt.getHeaders().get("kid"))
                && jwt.getExpiresAt()!=null && jwt.getIssuedAt()!=null && jwt.getNotBefore()!=null
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && Duration.between(jwt.getIssuedAt(),jwt.getExpiresAt()).getSeconds()<=600
                && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
                && "access".equals(jwt.getClaimAsString("token_use")) && jwt.getClaimAsString("sid")!=null
                && jwt.getId()!=null && jwt.getSubject()!=null && jwt.getClaim("ver")!=null;
            return valid?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }));return decoder;
    }
    public String issue(JwtEncoder encoder,UUID userId,UUID sessionId,String role,long version,boolean mfa,Instant now,Instant authenticatedAt){
        return issue(encoder,userId,sessionId,role,version,mfa,now,authenticatedAt,"pwd");
    }
    public String issue(JwtEncoder encoder,UUID userId,UUID sessionId,String role,long version,boolean mfa,Instant now,Instant authenticatedAt,String method){
        var claims=JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(userId.toString())
            .id(UUID.randomUUID().toString()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(600))
            .claim("sid",sessionId.toString()).claim("roles",List.of(role)).claim("ver",version)
            .claim("token_use","access").claim("amr",mfa?List.of(method,"otp"):List.of(method))
            .claim("auth_time",authenticatedAt.getEpochSecond()).build();
        var header=JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.rsa().getKeyID()).type("at+jwt").build();
        return encoder.encode(JwtEncoderParameters.from(header,claims)).getTokenValue();
    }
}
