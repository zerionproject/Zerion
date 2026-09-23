package org.zerionproject.core.db;

import org.zerionproject.core.util.StringUtils;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;

/**
 * A2-REG-AND-02: the passphrase bytes handed to the database library are
 * exactly what the former String path produced, so every existing database
 * still opens, while no String ever holds the key.
 */
public class SqlCipherPassphraseTest {

	@Test
	public void theBytePassphraseMatchesTheFormerStringEncoding() {
		Random r = new Random(7);
		for (int n = 0; n < 200; n++) {
			byte[] key = new byte[32];
			r.nextBytes(key);
			assertArrayEquals(StringUtils.toHexString(key)
					.getBytes(StandardCharsets.UTF_8),
					SqlCipherDatabase.hexPassphrase(key));
		}
		assertArrayEquals(new byte[0], SqlCipherDatabase.hexPassphrase(
				new byte[0]));
	}
}
