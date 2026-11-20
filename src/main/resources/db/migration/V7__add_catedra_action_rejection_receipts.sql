CREATE TABLE catedra_action_rejection (
    topic TEXT NOT NULL CHECK (topic ~ '[^[:space:]]'),
    partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
    record_offset BIGINT NOT NULL CHECK (record_offset >= 0),
    message_fingerprint VARCHAR(64) NOT NULL CHECK (message_fingerprint ~ '^[0-9a-f]{64}$'),
    rejection_reason VARCHAR(32) NOT NULL CHECK (rejection_reason IN (
        'INVALID_PAYLOAD', 'UNSUPPORTED_VERSION', 'UNSUPPORTED_EVENT', 'KEY_MISMATCH', 'GROUP_MISMATCH'
    )),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic, partition_id, record_offset)
);
