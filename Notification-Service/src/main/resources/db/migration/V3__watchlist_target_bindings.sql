-- Keep existing watch IDs and local canonical identities. One watch can bind to
-- multiple authoritative Threat identities without merging Threat-owned rows.
CREATE TABLE notification.watchlist_targets (
    watchlist_id UUID NOT NULL REFERENCES notification.watchlist(id) ON DELETE CASCADE,
    threat_target_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watchlist_id, threat_target_id)
);
CREATE INDEX notification_watchlist_targets_target_idx
    ON notification.watchlist_targets(threat_target_id);
