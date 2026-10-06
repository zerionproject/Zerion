package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MoneroEngineFailClosedTest {

	private final MoneroEngine engine = new NativeMoneroEngine();

	@Test
	public void engineReportsUnavailableWithoutNativeLibrary() {
		assertFalse(engine.isAvailable());
	}

	@Test
	public void createReturnsNullWhenUnavailable() {
		assertNull(engine.create("/tmp/w", "pw".toCharArray(), "English"));
	}

	@Test
	public void restoreReturnsNullWhenUnavailable() {
		assertNull(engine.restore("/tmp/w", "pw".toCharArray(),
				"seed words".toCharArray(), 0, new char[0]));
	}

	@Test
	public void openReturnsNullWhenUnavailable() {
		assertNull(engine.open("/tmp/w", "pw".toCharArray()));
	}

	@Test
	public void validateAddressIsFalseWhenUnavailable() {
		assertFalse(engine.validateAddress(
				"4AdkPJoxn7JCvAby9szgnt93MSEwdnxdhaASxbTBm6x5dCwmsDep2UYN4FhrX"
						+ "cnWDLGADAof8QzS4x2WuqcvxHk7fRVU2yF"));
	}
}
