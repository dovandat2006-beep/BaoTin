package com.baotin.search;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
class SearchHttp {
    private final SearchStore store;
    SearchHttp(SearchStore store) { this.store=store; }
    @GetMapping("/api/v1/search")
    SearchStore.Page search(@RequestParam String q, @RequestParam(required=false) String type,
            @RequestParam(required=false) String riskLevel, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return store.find(new SearchQuery(q,type,riskLevel,page,size));
    }
}
@RestControllerAdvice
class SearchErrors {
    static ProblemDetail problem(int status,String code,jakarta.servlet.http.HttpServletRequest request) {
        ProblemDetail p=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), status==400 ? "Check search parameters." : "Search is temporarily unavailable.");
        p.setProperty("code",code); p.setProperty("correlationId",request.getAttribute("correlationId")); return p;
    }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> invalid(Exception ignored,jakarta.servlet.http.HttpServletRequest request) { return ResponseEntity.badRequest().body(problem(400,"INVALID_SEARCH",request)); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(Exception ignored,jakarta.servlet.http.HttpServletRequest request) { return ResponseEntity.status(503).body(problem(503,"SEARCH_UNAVAILABLE",request)); }
}
@Configuration
class SearchSecurity {
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/api/v1/search","/actuator/health",
                "/actuator/health/liveness","/actuator/health/readiness").permitAll().anyRequest().denyAll())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->denied(res))
                .accessDeniedHandler((req,res,x)->denied(res))).build();
    }
    private static void denied(jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
        res.setStatus(403);res.setContentType("application/problem+json");
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\"FORBIDDEN\"}");
    }
}
