ALTER TABLE reservation_phone_submission
    ADD COLUMN publication_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publication_attempts >= 0),
    ADD COLUMN publication_lease_token UUID,
    ADD COLUMN publication_lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN next_publication_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT chk_phone_publication_lease CHECK (
        (publication_lease_token IS NULL) = (publication_lease_until IS NULL)
    );

CREATE INDEX ix_phone_publication_recovery ON reservation_phone_submission (next_publication_attempt_at, created_date)
    WHERE publication_state = 'PENDING';
