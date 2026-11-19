ALTER TABLE reservation_process
    DROP CONSTRAINT chk_reservation_process_status,
    DROP CONSTRAINT chk_reservation_process_held_details,
    DROP CONSTRAINT chk_reservation_process_waiting_patient;

ALTER TABLE reservation_process
    ADD CONSTRAINT chk_reservation_process_status CHECK (
        process_status IN ('PENDING_HOLD', 'HELD', 'CONFIRMATION_PENDING', 'WAITING_FOR_PHONE')
    ),
    ADD CONSTRAINT chk_reservation_process_held_details CHECK (
        process_status NOT IN ('HELD', 'CONFIRMATION_PENDING', 'WAITING_FOR_PHONE') OR
        (hold_id IS NOT NULL AND external_reservation_process_id IS NOT NULL AND expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_reservation_process_confirmation_patient CHECK (
        process_status NOT IN ('CONFIRMATION_PENDING', 'WAITING_FOR_PHONE') OR
        (patient_first_name IS NOT NULL AND patient_last_name IS NOT NULL)
    );
