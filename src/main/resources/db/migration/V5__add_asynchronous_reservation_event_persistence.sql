ALTER TABLE reservation_process
    ADD COLUMN request_event_id TEXT,
    ADD COLUMN external_reservation_id BIGINT,
    ADD COLUMN outcome_reason TEXT,
    DROP CONSTRAINT chk_reservation_process_status,
    DROP CONSTRAINT chk_reservation_process_held_details,
    DROP CONSTRAINT chk_reservation_process_confirmation_patient;

ALTER TABLE reservation_process
    ADD CONSTRAINT chk_reservation_process_status CHECK (process_status IN (
        'PENDING_HOLD','HELD','CONFIRMATION_PENDING','WAITING_FOR_PHONE',
        'PHONE_SUBMISSION_PENDING','WAITING_FOR_CONFIRMATION','CONFIRMED','EXPIRED','INVALID'
    )),
    ADD CONSTRAINT chk_reservation_process_held_details CHECK (
        process_status = 'PENDING_HOLD' OR
        (hold_id IS NOT NULL AND external_reservation_process_id IS NOT NULL AND expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_reservation_process_confirmation_patient CHECK (
        process_status IN ('PENDING_HOLD','HELD') OR
        (patient_first_name IS NOT NULL AND patient_last_name IS NOT NULL)
    ),
    ADD CONSTRAINT uq_reservation_process_request_event UNIQUE (request_event_id),
    ADD CONSTRAINT uq_reservation_process_reservation_id UNIQUE (external_reservation_id),
    ADD CONSTRAINT chk_reservation_process_request_event CHECK (
        request_event_id IS NULL OR request_event_id ~ '[^[:space:]]'
    ),
    ADD CONSTRAINT chk_reservation_process_phone_request CHECK (
        process_status NOT IN ('PHONE_SUBMISSION_PENDING','WAITING_FOR_CONFIRMATION','CONFIRMED') OR request_event_id IS NOT NULL
    ),
    ADD CONSTRAINT chk_reservation_process_final_reservation CHECK (
        (process_status = 'CONFIRMED') = (external_reservation_id IS NOT NULL)
        AND (external_reservation_id IS NULL OR external_reservation_id > 0)
    ),
    ADD CONSTRAINT chk_reservation_process_outcome_reason CHECK (
        (outcome_reason IS NULL OR outcome_reason ~ '^[A-Z][A-Z0-9_]*$')
        AND (process_status NOT IN ('EXPIRED','INVALID') OR outcome_reason IS NOT NULL)
        AND (outcome_reason IS NULL OR process_status IN ('WAITING_FOR_PHONE','EXPIRED','INVALID'))
    );

CREATE TABLE reservation_event_inbox (
    event_id TEXT PRIMARY KEY CHECK (event_id ~ '[^[:space:]]'),
    external_process_id TEXT NOT NULL CHECK (external_process_id ~ '[^[:space:]]'),
    payload TEXT NOT NULL CHECK (jsonb_typeof(payload::jsonb) = 'object'),
    fingerprint VARCHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    handling_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (handling_status IN ('PENDING','APPLIED','IGNORED','CONFLICT')),
    local_process_id BIGINT REFERENCES reservation_process(id) ON DELETE RESTRICT,
    content_conflict BOOLEAN NOT NULL DEFAULT FALSE,
    last_conflicting_fingerprint VARCHAR(64) CHECK (last_conflicting_fingerprint ~ '^[0-9a-f]{64}$'),
    last_conflict_date TIMESTAMP WITH TIME ZONE,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_date TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_reservation_event_handled CHECK (
        (handling_status = 'PENDING' AND handled_date IS NULL)
        OR (handling_status <> 'PENDING' AND handled_date IS NOT NULL AND local_process_id IS NOT NULL)
    ),
    CONSTRAINT chk_reservation_event_conflict CHECK (
        content_conflict = (last_conflicting_fingerprint IS NOT NULL)
        AND content_conflict = (last_conflict_date IS NOT NULL)
    )
);

CREATE INDEX ix_reservation_event_pending_process ON reservation_event_inbox (external_process_id, created_date, event_id)
    WHERE handling_status = 'PENDING' AND NOT content_conflict;
