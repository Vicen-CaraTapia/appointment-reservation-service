package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraActionStorageException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException.Reason;

/** Technical receipt, not business eventId idempotency. Never stores raw keys, bodies or parser errors. */
@Component
public final class RejectedCatedraActionStore {
	private final EntityManager entityManager;
	private final TransactionTemplate transactions;
	private final String expectedTopic;

	public RejectedCatedraActionStore(EntityManagerFactory factory, PlatformTransactionManager transactionManager,
			CatedraIntegrationProperties properties) {
		this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setTimeout(10);
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.expectedTopic = properties.kafka().appointmentActionsTopic();
	}

	/** Returns only after local commit. A persistence failure must prevent transport acknowledgment. */
	public void record(String topic, int partition, long offset, String messageKey, String payload, Reason reason) {
		if (!expectedTopic.equals(topic) || partition < 0 || offset < 0 || reason == null) {
			throw new IllegalArgumentException("Valid configured action delivery coordinates and reason are required");
		}
		try {
			String fingerprint = fingerprint(messageKey, payload);
			transactions.executeWithoutResult(transaction -> {
				entityManager.createNativeQuery("""
						INSERT INTO {h-schema}catedra_action_rejection
						(topic, partition_id, record_offset, message_fingerprint, rejection_reason)
						VALUES (:topic, :partition, :offset, :fingerprint, :reason)
						ON CONFLICT (topic, partition_id, record_offset) DO NOTHING
						""")
						.setParameter("topic", topic).setParameter("partition", partition).setParameter("offset", offset)
						.setParameter("fingerprint", fingerprint).setParameter("reason", reason.name()).executeUpdate();
				var stored = (Object[]) entityManager.createNativeQuery("""
						SELECT message_fingerprint, rejection_reason FROM {h-schema}catedra_action_rejection
						WHERE topic = :topic AND partition_id = :partition AND record_offset = :offset
						""")
						.setParameter("topic", topic).setParameter("partition", partition).setParameter("offset", offset).getSingleResult();
				if (!Objects.equals(fingerprint, stored[0]) || !Objects.equals(reason.name(), stored[1])) {
					throw new CatedraActionStorageException();
				}
			});
		} catch (RuntimeException failure) {
			// SQL diagnostics and remote values must not escape through nested causes.
			throw new CatedraActionStorageException();
		}
	}

	private static String fingerprint(String key, String payload) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			update(digest, key);
			update(digest, payload);
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("Required message fingerprint algorithm is unavailable");
		}
	}

	private static void update(MessageDigest digest, String value) {
		digest.update((byte) (value == null ? 0 : 1));
		if (value != null) {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
			digest.update(bytes);
		}
	}
}
