package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.system.SystemClock;

public class PasswordCryptoForTests {

	private PasswordCryptoForTests() {
	}

	public static CryptoComponent create() {
		SystemClock clock = new SystemClock();
		return new CryptoComponentImpl(() -> null, new ScryptKdf(clock),
				new Argon2idKdf(clock));
	}
}
