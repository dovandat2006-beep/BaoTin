package com.baotin.analytics.events;

import java.util.UUID;

/** Non-PII exception describing a rejected analytics event using safe metadata only. */
public class AnalyticsInvalidEventException extends RuntimeException {

    private final String topic;
    private final String reason;
    private final UUID eventId;

    public AnalyticsInvalidEventException(String topic, String reason) {
        this(topic, reason, null);
    }

    public AnalyticsInvalidEventException(String topic, String reason, UUID eventId) {
        super(formatMessage(topic, reason, eventId));
        this.topic = topic;
        this.reason = reason;
        this.eventId = eventId;
    }

    public String topic() {
        return topic;
    }

    public String reason() {
        return reason;
    }

    public UUID eventId() {
        return eventId;
    }

    private static String formatMessage(String topic, String reason, UUID eventId) {
        if (eventId != null) {
            return "Invalid analytics event on topic=" + topic + " reason=" + reason + " eventId=" + eventId;
        }
        return "Invalid analytics event on topic=" + topic + " reason=" + reason;
    }
}
