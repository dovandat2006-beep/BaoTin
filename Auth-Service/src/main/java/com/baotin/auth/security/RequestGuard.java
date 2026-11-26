package com.baotin.auth.security;

import com.baotin.auth.api.ApiFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class RequestGuard extends OncePerRequestFilter {
    private final AttemptLimits limits;
    private final ProxyAddresses gateways;private final int rateLimit,windowSeconds;
    public RequestGuard(AttemptLimits limits,@Value("${auth.trusted-gateway-cidrs:}") String trusted,@Value("${auth.auth-write-limit:30}") int rateLimit,@Value("${auth.rate-window-seconds:300}") int windowSeconds){this.limits=limits;gateways=new ProxyAddresses(trusted);if(rateLimit<1||windowSeconds<1)throw new IllegalArgumentException("Rate settings must be positive");this.rateLimit=rateLimit;this.windowSeconds=windowSeconds;}
    private void error(HttpServletResponse response,int status,String code)throws IOException{
        response.setStatus(status);response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"https://baotin.example/errors/"+code.toLowerCase(java.util.Locale.ROOT).replace('_','-')+"\",\"title\":\"Request rejected\",\"status\":"+status+",\"code\":\""+code+"\",\"correlationId\":\""+response.getHeader("X-Correlation-Id")+"\"}");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        response.setHeader("Cache-Control","no-store");String correlation=java.util.UUID.randomUUID().toString();String client=request.getRemoteAddr();
        request.setAttribute("correlationId",correlation);response.setHeader("X-Correlation-Id",correlation);
        if(gateways.trusted(client)){
            String forwarded=request.getHeader("X-BaoTin-Client-IP");
            if(forwarded!=null)try{client=ProxyAddresses.literal(forwarded).getHostAddress();}catch(IllegalArgumentException e){error(response,400,"FORWARDED_IP_INVALID");return;}
            String incoming=request.getHeader("X-Correlation-Id");
            if(incoming!=null&&incoming.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))correlation=java.util.UUID.fromString(incoming).toString();
        }
        request.setAttribute("correlationId",correlation);response.setHeader("X-Correlation-Id",correlation);
        if(!Set.of("POST","PATCH").contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/v1/auth/")){chain.doFilter(request,response);return;}
        try{limits.check("auth-write-ip",client,rateLimit,windowSeconds);}catch(ApiFailure e){response.setHeader("Retry-After",Integer.toString(windowSeconds));error(response,429,"RATE_LIMITED");return;}catch(org.springframework.dao.DataAccessException e){error(response,503,"RATE_LIMIT_UNAVAILABLE");return;}
        if(request.getContentLengthLong()>65536){error(response,413,"REQUEST_TOO_LARGE");return;}
        byte[] body=request.getInputStream().readNBytes(65537);
        if(body.length>65536){error(response,413,"REQUEST_TOO_LARGE");return;}
        var wrapped=new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){
                var in=new ByteArrayInputStream(body);
                return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new IllegalStateException("Async read not supported");}};
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        };chain.doFilter(wrapped,response);
    }
}
