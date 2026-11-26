package com.baotin.notification.api;

import java.net.URI;

/** Flat RFC 9457 body: custom fields stay top-level, matching the published contract. */
public record ProblemResponse(URI type, String title, int status, String code, String detail,
                              URI instance, String correlationId) {}
