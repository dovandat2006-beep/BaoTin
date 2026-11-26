package com.baotin.auth.security;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwords(){return new BoundedPasswordEncoder(new Argon2PasswordEncoder(16,32,1,19456,2),2);}
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<SessionGuard> disableSessionServletRegistration(SessionGuard filter){var r=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);r.setEnabled(false);return r;}
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<RequestGuard> disableRequestServletRegistration(RequestGuard filter){var r=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);r.setEnabled(false);return r;}
    @Bean SecurityFilterChain security(HttpSecurity http,SessionGuard guard,RequestGuard requests,@Value("${auth.allowed-origins}") String origins) throws Exception {
        var cors=new CorsConfiguration();cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        cors.setAllowedMethods(java.util.List.of("GET","POST","PATCH","DELETE"));
        cors.setAllowedHeaders(java.util.List.of("Authorization","Content-Type","X-CSRF-TOKEN"));cors.setAllowCredentials(true);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",cors);
        var roles=new JwtGrantedAuthoritiesConverter();roles.setAuthoritiesClaimName("roles");roles.setAuthorityPrefix("ROLE_");
        var converter=new JwtAuthenticationConverter();converter.setJwtGrantedAuthoritiesConverter(roles);
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c->c.disable()) // Bearer APIs do not use cookies. Web controller validates exact Origin + session-bound CSRF.
            .cors(c->c.configurationSource(source))
            .authorizeHttpRequests(a->a
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/.well-known/jwks.json","/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/mfa/challenges","/api/v1/auth/mfa/email/challenges","/api/v1/auth/verify-email","/api/v1/auth/forgot-password","/api/v1/auth/reset-password").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/web/login","/api/v1/auth/web/refresh","/api/v1/auth/web/logout","/api/v1/auth/web/mfa/challenges","/api/v1/auth/web/mfa/email/challenges").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/oauth2/requests","/api/v1/auth/oauth2/tokens","/api/v1/auth/web/oauth2/tokens").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/auth/oauth2/callback/google","/api/v1/auth/oauth2/callback/facebook").permitAll()
                .requestMatchers("/api/v1/auth/**").authenticated().anyRequest().denyAll())
            .oauth2ResourceServer(resource->resource.jwt(j->j.jwtAuthenticationConverter(converter)).authenticationEntryPoint((q,r,x)->ServletProblems.write(q,r,401,"UNAUTHORIZED")))
            .addFilterAfter(guard,BearerTokenAuthenticationFilter.class)
            .addFilterBefore(requests,BearerTokenAuthenticationFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->ServletProblems.write(q,r,401,"UNAUTHORIZED"))
                .accessDeniedHandler((q,r,x)->ServletProblems.write(q,r,403,"FORBIDDEN")));
        return http.build();
    }
}
