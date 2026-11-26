package com.baotin.gateway;

import java.net.URI;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/** Operator-reviewed service addresses; public endpoints are opt-in, never discovered. */
@ConfigurationProperties("gateway.catalog")
public record ServiceCatalog(Map<String,URI> services,List<Endpoint> endpoints) {
    private static final String REPORT="report";
    private static final String NOTIFICATION="notification";
    public record Endpoint(String id,String service,String path,List<HttpMethod> methods,boolean anonymous) {
        public Endpoint {methods=methods==null?List.of():List.copyOf(methods);}
    }
    private static final Set<String> KNOWN=Set.of("auth","threat",REPORT,"reputation","model-inference",NOTIFICATION,"search","campaign","analytics");
    private static final Map<String,Set<String>> PUBLIC=Map.of(
        "threat",Set.of("scans","threats"),REPORT,Set.of("reports","moderation"),
        NOTIFICATION,Set.of("notifications","devices","watchlist"),"search",Set.of("search"),
        "campaign",Set.of("campaigns"),"analytics",Set.of("analytics"));
    private static final Set<HttpMethod> METHODS=Set.of(HttpMethod.GET,HttpMethod.POST,HttpMethod.PATCH,HttpMethod.DELETE,HttpMethod.PUT);
    public ServiceCatalog {
        services=services==null?Map.of():Map.copyOf(services);
        endpoints=endpoints==null?List.of():List.copyOf(endpoints);
        if(!KNOWN.containsAll(services.keySet()) || endpoints.size()>128)throw invalid();
        for(URI uri:services.values()){
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null ||
               uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("","/").contains(uri.getPath()) || uri.getPort()==0 || uri.getPort()>65535)throw invalid();
        }
        Set<String> ids=new HashSet<>(),pairs=new HashSet<>();
        for(Endpoint ep:endpoints){
            if((REPORT.equals(ep.service()) || NOTIFICATION.equals(ep.service())) && ep.anonymous()) throw invalid();
            if(ep.id()==null || !ep.id().matches("[a-z][a-z0-9-]{0,63}") || !ids.add(ep.id()) ||
               !PUBLIC.containsKey(ep.service()) || !services.containsKey(ep.service()) || ep.path()==null ||
               !ep.path().matches("/api/v1/[a-z][a-z0-9-]*(/([a-zA-Z0-9_-]+|\\{[a-zA-Z][a-zA-Z0-9]*\\}))*+") ||
               !PUBLIC.get(ep.service()).contains(ep.path().split("/")[3]) ||
               ep.methods()==null || ep.methods().isEmpty() || !METHODS.containsAll(ep.methods()))throw invalid();
            for(HttpMethod method:ep.methods())if(!pairs.add(method.name()+" "+ep.path().replaceAll("\\{[^}]+}","{parameter}")))throw invalid();
            PathPatternParser.defaultInstance.parse(ep.path());
        }
        endpoints=endpoints.stream().sorted(Comparator.comparing(ep->PathPatternParser.defaultInstance.parse(ep.path()),
            org.springframework.web.util.pattern.PathPattern.SPECIFICITY_COMPARATOR)).toList();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid reviewed service/endpoint configuration");}
    public Optional<Endpoint> endpoint(ServerWebExchange exchange){
        String path=exchange.getRequest().getURI().getRawPath();
        if(path.contains("%") || path.contains("\\") || path.contains("//"))return Optional.empty();
        PathContainer parsed=exchange.getRequest().getPath().pathWithinApplication();
        return endpoints.stream().filter(ep->ep.methods().contains(exchange.getRequest().getMethod()) &&
            PathPatternParser.defaultInstance.parse(ep.path()).matches(parsed)).findFirst();
    }
    public static List<String> origins(String configured){
        List<String> result=new ArrayList<>();
        try{for(String value:configured.split(",",-1)){
            String origin=value.trim();URI uri=URI.create(origin);
            boolean local=Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
            if(uri.getHost()==null || origin.contains("*") || uri.getUserInfo()!=null || !uri.getPath().isEmpty() ||
               uri.getQuery()!=null || uri.getFragment()!=null ||
               !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()))))throw invalid();
            if(!result.contains(origin))result.add(origin);
        }}catch(Exception e){throw new IllegalArgumentException("Exact HTTPS Web origins required; local HTTP is development-only");}
        return List.copyOf(result);
    }
}
