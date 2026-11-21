package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.event;

import java.util.Objects;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraActionProcessingException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.persistence.RejectedCatedraActionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ProcessReservationActionUseCase;

/** Thin Kafka boundary. Offsets advance only after a durable business receipt or rejection receipt. */
@Component
public final class CatedraAppointmentActionListener {
	private final CatedraAppointmentActionDecoder decoder;
	private final ProcessReservationActionUseCase actions;
	private final RejectedCatedraActionStore rejected;

	public CatedraAppointmentActionListener(CatedraAppointmentActionDecoder decoder,
			ProcessReservationActionUseCase actions, RejectedCatedraActionStore rejected) {
		this.decoder = Objects.requireNonNull(decoder);
		this.actions = Objects.requireNonNull(actions);
		this.rejected = Objects.requireNonNull(rejected);
	}

	@KafkaListener(id = "catedraAppointmentActionListener", topics = "${catedra.kafka.appointment-actions-topic}",
			groupId = "${catedra.kafka.consumer-group-id}",
			containerFactory = "catedraKafkaListenerContainerFactory")
	public void receive(ConsumerRecord<String, String> record) {
		try {
			var event = decoder.decode(record.key(), record.value());
			actions.process(event);
		} catch (InvalidCatedraActionException invalid) {
			try {
				rejected.record(record.topic(), record.partition(), record.offset(), record.key(), record.value(), invalid.reason());
			} catch (RuntimeException storageFailure) {
				throw new CatedraActionProcessingException();
			}
		} catch (RuntimeException processingFailure) {
			throw new CatedraActionProcessingException();
		}
	}
}
