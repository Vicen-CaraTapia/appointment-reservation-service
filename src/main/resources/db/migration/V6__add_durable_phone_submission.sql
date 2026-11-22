CREATE TABLE reservation_phone_submission (
    event_id TEXT PRIMARY KEY CHECK (event_id ~ '[^[:space:]]'),
    local_process_id BIGINT NOT NULL REFERENCES reservation_process(id) ON DELETE RESTRICT,
    request_event_id TEXT NOT NULL REFERENCES reservation_event_inbox(event_id) ON DELETE RESTRICT,
    external_process_id TEXT NOT NULL CHECK (external_process_id ~ '[^[:space:]]'),
    hold_id TEXT NOT NULL CHECK (hold_id ~ '[^[:space:]]'),
    technical_group_id TEXT NOT NULL CHECK (technical_group_id ~ '[^[:space:]]'),
    normalized_phone VARCHAR(16) NOT NULL CHECK (normalized_phone ~ '^[+]?[0-9]{7,15}$'),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    publication_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (publication_state IN ('PENDING','PUBLISHED','CLOSED')),
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_date TIMESTAMP WITH TIME ZONE,
    closed_date TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_phone_submission_process_pair UNIQUE (event_id, local_process_id),
    CONSTRAINT chk_phone_submission_dates CHECK (
        (publication_state = 'PENDING' AND published_date IS NULL AND closed_date IS NULL)
        OR (publication_state = 'PUBLISHED' AND published_date IS NOT NULL AND closed_date IS NULL)
        OR (publication_state = 'CLOSED' AND closed_date IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_phone_submission_active_process ON reservation_phone_submission(local_process_id)
    WHERE publication_state IN ('PENDING','PUBLISHED');

ALTER TABLE reservation_process
    ADD COLUMN current_phone_submission_event_id TEXT,
    ADD CONSTRAINT fk_reservation_process_current_phone
        FOREIGN KEY (current_phone_submission_event_id, id)
        REFERENCES reservation_phone_submission(event_id, local_process_id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_reservation_process_current_phone CHECK (
        process_status NOT IN ('PHONE_SUBMISSION_PENDING','WAITING_FOR_CONFIRMATION')
        OR current_phone_submission_event_id IS NOT NULL
    );
