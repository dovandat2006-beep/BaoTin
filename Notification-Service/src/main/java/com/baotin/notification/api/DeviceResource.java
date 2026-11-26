package com.baotin.notification.api;

import java.time.Instant;

/** Provider tokens are write-only and never returned to any client. */
public record DeviceResource(String id, String platform, String deviceId, boolean active,
                             Instant createdAt, Instant lastSeenAt) {}
