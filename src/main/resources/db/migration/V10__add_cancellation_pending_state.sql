ALTER TABLE reservation_process
    ADD COLUMN cancellation_reason VARCHAR(500),
    ADD COLUMN cancellation_requested_at TIMESTAMP WITH TIME ZONE,
    DROP CONSTRAINT chk_reservation_process_status,
    DROP CONSTRAINT chk_reservation_process_confirmation_patient,
    DROP CONSTRAINT chk_reservation_process_final_reservation;

ALTER TABLE reservation_process
    ADD CONSTRAINT chk_reservation_process_status CHECK (process_status IN (
        'PENDING_HOLD','HELD','CONFIRMATION_PENDING','WAITING_FOR_PHONE',
        'PHONE_SUBMISSION_PENDING','WAITING_FOR_CONFIRMATION','CANCELLATION_PENDING',
        'CONFIRMED','EXPIRED','INVALID','CANCELLED','FAILED'
    )),
    ADD CONSTRAINT chk_reservation_process_confirmation_patient CHECK (
        process_status IN ('PENDING_HOLD','HELD') OR
        (patient_first_name IS NOT NULL AND patient_last_name IS NOT NULL) OR
        (process_status IN ('CONFIRMED','CANCELLATION_PENDING','CANCELLED','FAILED') AND rest_verified_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_reservation_process_final_reservation CHECK (
        (process_status NOT IN ('CONFIRMED','CANCELLATION_PENDING','CANCELLED','FAILED') OR external_reservation_id IS NOT NULL)
        AND (external_reservation_id IS NULL OR external_reservation_id > 0)
        AND (external_reservation_id IS NULL OR process_status IN ('CONFIRMED','CANCELLATION_PENDING') OR rest_verified_at IS NOT NULL)
        AND (process_status <> 'FAILED' OR (rest_observed_status = 'FAILED' AND rest_verified_at IS NOT NULL))
    ),
    ADD CONSTRAINT chk_reservation_process_cancellation_reason CHECK (
        cancellation_reason IS NULL OR char_length(cancellation_reason) <= 500
    ),
    ADD CONSTRAINT chk_reservation_process_cancellation_requested_at CHECK (
        process_status <> 'CANCELLATION_PENDING' OR cancellation_requested_at IS NOT NULL
    );
