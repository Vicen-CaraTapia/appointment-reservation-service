package ar.edu.um.prog2.appointmentreservation.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;

/** Generates test-only keys in memory and writes them only into temporary directories. */
public final class CatalogJwtTestSupport {

	private CatalogJwtTestSupport() {
	}

	public static KeyPair signingKeyPair() {
		return SigningKey.PAIR;
	}

	public static KeyPair generateRsaKeyPair(int bits) {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(bits);
			return generator.generateKeyPair();
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Cannot generate test RSA key");
		}
	}

	public static Path writePrivateKey(Path directory, PrivateKey key) throws IOException {
		var path = Files.createTempFile(directory, "catalog-test-key-", ".pem");
		var body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
		Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n",
				StandardCharsets.US_ASCII);
		return path;
	}

	private static final class SigningKey {
		private static final KeyPair PAIR = generateRsaKeyPair(2048);
	}
}
