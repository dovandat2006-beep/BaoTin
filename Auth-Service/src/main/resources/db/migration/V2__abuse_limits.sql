CREATE TABLE auth.rate_limits (
    bucket_key VARCHAR(64) NOT NULL,
    window_id BIGINT NOT NULL,
    attempts INTEGER NOT NULL,
    PRIMARY KEY(bucket_key,window_id)
);
