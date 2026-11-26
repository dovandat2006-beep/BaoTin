package com.baotin.reputation.api;

import java.net.URI;

public record ProblemResponse(URI type, String title, int status, String code, String detail,
                              URI instance, String correlationId) {
}
