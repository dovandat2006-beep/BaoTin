package com.baotin.notification.api;

import java.time.Instant;
import java.util.Map;

public record NotificationResource(String id, String type, String title, String body, Map<String, Object> data,
                                   Instant createdAt, Instant readAt, boolean read) {}
