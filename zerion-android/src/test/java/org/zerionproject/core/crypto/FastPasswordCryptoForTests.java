package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.system.SystemClock;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class FastPasswordCryptoForTests {

	private FastPasswordCryptoForTests() {
	}

	public static CryptoComponent create() {
		SystemClock clock = new SystemClock();
		return new CryptoComponentImpl(() -> null, new ScryptKdf(clock),
				new Argon2idKdf(clock) {
					@Override
					public int chooseCostParameter() {
						return ((64 * 1024) << 8) | 2;
					}

					@Override
					public SecretKey deriveKey(char[] password, byte[] salt,
							int cost) {
						try {
							Mac mac = Mac.getInstance("HmacSHA256");
							mac.init(new SecretKeySpec(salt, "HmacSHA256"));
							return new SecretKey(mac.doFinal(new String(password)
									.getBytes(StandardCharsets.UTF_8)));
						} catch (Exception e) {
							throw new AssertionError(e);
						}
					}
				});
	}
}
