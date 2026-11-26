package com.baotin.analytics.integration;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

@Configuration
public class UpstreamClientConfig {

    @Bean("reportRestClient")
    RestClient reportRestClient(
            @Value("${analytics.report-uri:http://localhost:8183}") String baseUrl,
            @Value("${analytics.upstream-timeout-ms:3000}") long timeoutMs) {
        return buildClient(baseUrl, timeoutMs);
    }

    @Bean("threatRestClient")
    RestClient threatRestClient(
            @Value("${analytics.threat-uri:http://localhost:8182}") String baseUrl,
            @Value("${analytics.upstream-timeout-ms:3000}") long timeoutMs) {
        return buildClient(baseUrl, timeoutMs);
    }

    static <T> T getJson(
            RestClient restClient,
            String upstream,
            Function<UriBuilder, URI> uriFunction,
            String accessToken,
            String correlationId,
            Class<T> responseType) {
        try {
            T body = restClient.get()
                    .uri(uriFunction)
                    .headers(headers -> {
                        if (accessToken != null && !accessToken.isBlank()) {
                            headers.setBearerAuth(accessToken);
                        }
                        if (correlationId != null && !correlationId.isBlank()) {
                            headers.set("X-Correlation-Id", correlationId);
                        }
                    })
                    .retrieve()
                    .body(responseType);
            if (body == null) {
                throw new UpstreamUnavailableException(upstream);
            }
            return body;
        } catch (RestClientException | HttpMessageConversionException exception) {
            throw new UpstreamUnavailableException(upstream);
        }
    }

    private static RestClient buildClient(String baseUrl, long timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("upstream baseUrl must not be blank");
        }
        if (timeoutMs <= 0 || timeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("analytics.upstream-timeout-ms must be between 1 and Integer.MAX_VALUE");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(timeoutMs);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
