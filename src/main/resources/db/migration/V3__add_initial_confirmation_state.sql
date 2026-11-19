ALTER TABLE reservation_process
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN patient_first_name VARCHAR(100),
    ADD COLUMN patient_last_name VARCHAR(100),
    DROP CONSTRAINT chk_reservation_process_status,
    DROP CONSTRAINT chk_reservation_process_held_details;

ALTER TABLE reservation_process
    ADD CONSTRAINT chk_reservation_process_status
        CHECK (process_status IN ('PENDING_HOLD', 'HELD', 'WAITING_FOR_PHONE')),
    ADD CONSTRAINT chk_reservation_process_held_details CHECK (
        process_status NOT IN ('HELD', 'WAITING_FOR_PHONE') OR
        (hold_id IS NOT NULL AND external_reservation_process_id IS NOT NULL AND expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_reservation_process_patient_first_name CHECK (
        patient_first_name IS NULL OR
        (char_length(patient_first_name) BETWEEN 2 AND 100 AND patient_first_name ~ '[^[:space:]]')
    ),
    ADD CONSTRAINT chk_reservation_process_patient_last_name CHECK (
        patient_last_name IS NULL OR
        (char_length(patient_last_name) BETWEEN 2 AND 100 AND patient_last_name ~ '[^[:space:]]')
    ),
    ADD CONSTRAINT chk_reservation_process_waiting_patient CHECK (
        process_status <> 'WAITING_FOR_PHONE' OR
        (patient_first_name IS NOT NULL AND patient_last_name IS NOT NULL)
    );
