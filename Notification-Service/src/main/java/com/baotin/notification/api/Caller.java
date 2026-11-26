package com.baotin.notification.api;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The JWT subject is the only user identity accepted from a request. */
public final class Caller {
    private Caller() {}

    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw ApiFailure.unauthorized();
        }
    }
}
