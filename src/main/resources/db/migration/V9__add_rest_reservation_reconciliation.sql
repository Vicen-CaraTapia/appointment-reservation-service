ALTER TABLE reservation_process
    ADD COLUMN rest_observed_status VARCHAR(20),
    ADD COLUMN rest_verified_at TIMESTAMP WITH TIME ZONE,
    DROP CONSTRAINT chk_reservation_process_status,
    DROP CONSTRAINT chk_reservation_process_confirmation_patient,
    DROP CONSTRAINT chk_reservation_process_phone_request,
    DROP CONSTRAINT chk_reservation_process_final_reservation;

ALTER TABLE reservation_process
    ADD CONSTRAINT chk_reservation_process_status CHECK (process_status IN (
        'PENDING_HOLD','HELD','CONFIRMATION_PENDING','WAITING_FOR_PHONE',
        'PHONE_SUBMISSION_PENDING','WAITING_FOR_CONFIRMATION','CONFIRMED','EXPIRED','INVALID','CANCELLED','FAILED'
    )),
    ADD CONSTRAINT chk_reservation_process_rest_evidence CHECK (
        (rest_observed_status IS NULL) = (rest_verified_at IS NULL)
        AND (rest_observed_status IS NULL OR rest_observed_status IN ('PHONE_PENDING','CONFIRMED','CANCELLED','FAILED'))
        AND (rest_verified_at IS NULL OR external_reservation_id IS NOT NULL)
    ),
    ADD CONSTRAINT chk_reservation_process_confirmation_patient CHECK (
        process_status IN ('PENDING_HOLD','HELD') OR
        (patient_first_name IS NOT NULL AND patient_last_name IS NOT NULL) OR
        (process_status IN ('CONFIRMED','CANCELLED','FAILED') AND rest_verified_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_reservation_process_phone_request CHECK (
        (process_status NOT IN ('PHONE_SUBMISSION_PENDING','WAITING_FOR_CONFIRMATION') OR request_event_id IS NOT NULL)
        AND (process_status <> 'CONFIRMED' OR request_event_id IS NOT NULL
            OR (rest_observed_status = 'CONFIRMED' AND rest_verified_at IS NOT NULL))
    ),
    ADD CONSTRAINT chk_reservation_process_final_reservation CHECK (
        (process_status NOT IN ('CONFIRMED','CANCELLED','FAILED') OR external_reservation_id IS NOT NULL)
        AND (external_reservation_id IS NULL OR external_reservation_id > 0)
        AND (external_reservation_id IS NULL OR process_status = 'CONFIRMED' OR rest_verified_at IS NOT NULL)
        AND (process_status <> 'FAILED' OR (rest_observed_status = 'FAILED' AND rest_verified_at IS NOT NULL))
    );
