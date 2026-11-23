-- Kafka cancellation evidence lives in the inbox; it must not fabricate a REST observation.
ALTER TABLE reservation_process DROP CONSTRAINT chk_reservation_process_final_reservation;
ALTER TABLE reservation_process ADD CONSTRAINT chk_reservation_process_final_reservation CHECK (
    (process_status NOT IN ('CONFIRMED','CANCELLATION_PENDING','CANCELLED','FAILED') OR external_reservation_id IS NOT NULL)
    AND (external_reservation_id IS NULL OR external_reservation_id > 0)
    AND (external_reservation_id IS NULL OR process_status IN ('CONFIRMED','CANCELLATION_PENDING')
        OR rest_verified_at IS NOT NULL OR (process_status = 'CANCELLED' AND request_event_id IS NOT NULL))
    AND (process_status <> 'FAILED' OR (rest_observed_status = 'FAILED' AND rest_verified_at IS NOT NULL))
);
