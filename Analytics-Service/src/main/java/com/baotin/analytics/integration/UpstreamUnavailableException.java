package com.baotin.analytics.integration;

public class UpstreamUnavailableException extends RuntimeException {

    private final String upstream;

    public UpstreamUnavailableException(String upstream) {
        super("Upstream service unavailable: " + upstream);
        this.upstream = upstream;
    }

    public String upstream() {
        return upstream;
    }
}
