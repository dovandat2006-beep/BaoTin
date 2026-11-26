package com.baotin.gateway;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.http.HttpMethod;

class ServiceCatalogTests {
    private Map<String,URI> services(){return Map.of("threat",URI.create("http://127.0.0.1:8182"),"model-inference",URI.create("http://127.0.0.1:8185"));}
    private ServiceCatalog.Endpoint ep(String id,String service,String path,boolean anonymous){return new ServiceCatalog.Endpoint(id,service,path,List.of(HttpMethod.GET),anonymous);}
    @Test void serviceAddressDoesNotExposeAnyEndpoint(){assertTrue(new ServiceCatalog(services(),null).endpoints().isEmpty());}
    @Test void privateAndWrongNamespaceEndpointsAreRejected(){
        for(var endpoint:List.of(ep("ai","model-inference","/api/v1/inference",false),ep("internal","threat","/internal/v1/scans",false),
            ep("wildcard","threat","/api/v1/scans/**",false),ep("management","threat","/actuator/health",true),
            ep("auth","threat","/api/v1/auth/login",true),ep("bad","threat","/api/v1/scans/../auth",true))){
            assertThrows(IllegalArgumentException.class,()->new ServiceCatalog(services(),List.of(endpoint)));
        }
    }
    @Test void unknownTargetAndDuplicatePatternsFailClosed(){
        assertThrows(IllegalArgumentException.class,()->new ServiceCatalog(Map.of("unreviewed",URI.create("http://127.0.0.1:8182")),List.of()));
        assertThrows(IllegalArgumentException.class,()->new ServiceCatalog(services(),List.of(ep("one","threat","/api/v1/scans/{id}",false),ep("two","threat","/api/v1/scans/{uuid}",true))));
    }
    @Test void exactEndpointTakesPrecedenceOverAnonymousParameterPattern(){
        var c=new ServiceCatalog(services(),List.of(ep("generic","threat","/api/v1/scans/{id}",true),ep("history","threat","/api/v1/scans/history",false)));
        assertEquals("history",c.endpoints().getFirst().id());assertFalse(c.endpoints().getFirst().anonymous());
    }
    @Test void targetCannotCarryCredentialsQueryPathOrUnsafeScheme(){
        for(String value:List.of("file:///tmp/private","http://user:password@localhost:8182","http://localhost:8182/internal","http://localhost:8182?token=x","http://localhost:8182#fragment","http://localhost:99999")){
            assertThrows(IllegalArgumentException.class,()->new ServiceCatalog(Map.of("threat",URI.create(value)),List.of()));
        }
    }
    @Test void reportAddressAloneDoesNotExposeEndpoints(){
        assertTrue(new ServiceCatalog(Map.of("report",URI.create("http://127.0.0.1:8183")),null).endpoints().isEmpty());
    }
    @Test void notificationAddressAloneExposesNothingAndAnonymousConfigurationIsRejected(){
        var addresses=Map.of("notification",URI.create("http://127.0.0.1:8186"));
        assertTrue(new ServiceCatalog(addresses,null).endpoints().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new ServiceCatalog(addresses,List.of(ep("leak","notification","/api/v1/watchlist",true))));
    }
    @Test void shippedExternalReplacementPreservesEveryReportAndNotificationRoute() throws Exception {
        Properties properties=new Properties();try(var reader=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("manual-routes.properties.example"))){properties.load(reader);}
        Map<String,Object> values=new HashMap<>();properties.forEach((key,value)->values.put(key.toString(),value));
        for(String service:List.of("threat","notification","report"))values.put("gateway.catalog.services."+service,"http://127.0.0.1:8182");
        var source=new org.springframework.core.env.MapPropertySource("reviewed-external",values);
        var catalog=new org.springframework.boot.context.properties.bind.Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(source))
            .bind("gateway.catalog",org.springframework.boot.context.properties.bind.Bindable.of(ServiceCatalog.class)).get();
        assertEquals(31,catalog.endpoints().size());
        for(String path:List.of("/api/v1/reports","/api/v1/reports/me","/api/v1/reports/{id}/evidence","/api/v1/moderation/reports/{id}/verify","/api/v1/watchlist","/api/v1/notifications/devices"))
            assertTrue(catalog.endpoints().stream().anyMatch(ep->ep.path().equals(path) && !ep.anonymous()),path);
        assertEquals(13,catalog.endpoints().stream().filter(ep->ep.service().equals("report")).count());
        assertTrue(catalog.endpoints().stream().noneMatch(ep->ep.path().startsWith("/internal/")));
    }
    @Test void internetHttpsOriginsAreExactAndLocalHttpIsSupported(){
        assertEquals(List.of("https://web.baotin.test","http://localhost:8081"),ServiceCatalog.origins("https://web.baotin.test,http://localhost:8081,https://web.baotin.test"));
    }
    @Test void unsafeCredentialedCorsOriginConfigurationFailsAtStartup(){
        for(String value:List.of("*","https://*.example.test","http://web.example.test","https://web.example.test/","https://web.example.test/path","https://user:password@web.example.test","https://web.example.test?x=1","null","")){
            assertThrows(IllegalArgumentException.class,()->ServiceCatalog.origins(value));
        }
    }
    @Test void analyticsAddressAloneOrEmptyReplacementExposesNothing(){
        var addresses=Map.of("analytics",URI.create("http://127.0.0.1:8189"));
        assertTrue(new ServiceCatalog(addresses,null).endpoints().isEmpty());
        assertTrue(new ServiceCatalog(addresses,List.of()).endpoints().isEmpty());
    }
    @Test void shippedConfigurationExposesOnlyReviewedAnalyticsAdminReads() throws Exception {
        Properties shipped=new Properties();
        try(var input=ServiceCatalogTests.class.getResourceAsStream("/application.properties")) {
            assertNotNull(input);shipped.load(input);
        }
        var analyticsKeys=shipped.stringPropertyNames().stream()
            .filter(key->key.matches("gateway\\.catalog\\.endpoints\\[\\d+]\\.service")&&"analytics".equals(shipped.getProperty(key)))
            .toList();
        assertEquals(4,analyticsKeys.size());
        var paths=analyticsKeys.stream().map(key->shipped.getProperty(key.replace(".service",".path"))).collect(Collectors.toSet());
        assertEquals(Set.of("/api/v1/analytics/admin/summary","/api/v1/analytics/admin/queue","/api/v1/analytics/admin/counts","/api/v1/analytics/admin/trends"),paths);
        for(String key:analyticsKeys){
            assertEquals("GET",shipped.getProperty(key.replace(".service",".methods[0]")));
            assertEquals("false",shipped.getProperty(key.replace(".service",".anonymous")));
        }
    }
    @Test void analyticsAddressDoesNotExtendAnExternalReplacement(){
        var addresses=Map.of("analytics",URI.create("http://127.0.0.1:8189"),"threat",URI.create("http://127.0.0.1:8182"));
        var reviewed=ep("history","threat","/api/v1/scans/history",false);
        assertEquals(List.of(reviewed),new ServiceCatalog(addresses,List.of(reviewed)).endpoints());
    }
    @Test void explicitAnalyticsOptInContainsOnlyReviewedAdminReadEndpoints(){
        var catalog=new ServiceCatalog(Map.of("analytics",URI.create("http://127.0.0.1:8189")),AnalyticsEndpoints.all());
        assertEquals(4,catalog.endpoints().size());
        assertEquals(Set.of("analytics-summary","analytics-queue","analytics-counts","analytics-trends"),
            catalog.endpoints().stream().map(ServiceCatalog.Endpoint::id).collect(Collectors.toSet()));
        assertTrue(catalog.endpoints().stream().allMatch(ep->
            ep.service().equals("analytics")&&!ep.anonymous()&&ep.methods().equals(List.of(HttpMethod.GET))));
    }
    @Test void analyticsEndpointWithoutConfiguredServiceFailsClosed(){
        assertThrows(IllegalArgumentException.class,()->new ServiceCatalog(
            Map.of("threat",URI.create("http://127.0.0.1:8182")),
            List.of(new ServiceCatalog.Endpoint("analytics-summary","analytics",
                "/api/v1/analytics/admin/summary",List.of(HttpMethod.GET),false))));
    }
}
