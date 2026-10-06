package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.system.SystemClock;

public final class CryptoForTests {

	private CryptoForTests() {
	}

	public static CryptoComponent create() {
		return new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
	}
}
