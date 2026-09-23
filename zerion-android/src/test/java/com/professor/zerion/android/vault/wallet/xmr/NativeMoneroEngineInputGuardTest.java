package com.professor.zerion.android.vault.wallet.xmr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every string that would reach a native parser is checked on the JVM side
 * first: it must be present, bounded and printable ASCII. An address of
 * several kilobytes, one with control or non-ASCII characters, or a missing
 * one never crosses into native code, whatever the native library would make
 * of it.
 */
public class NativeMoneroEngineInputGuardTest {

	private static final String ADDRESS =
			"4AdkPJoxn7JCvAby9szgnt93MSEwdnxdhaASxbTBm6x5dCwmsDep2UYN4FhrX"
					+ "cnWDLGADAof8QzS4x2WuqcvxHk7fRVU2yF";

	@Test
	public void acceptableStringsArePresentBoundedAndPrintableAscii() {
		assertTrue(NativeMoneroEngine.acceptableJniString(ADDRESS,
				NativeMoneroEngine.MAX_ADDRESS_CHARS));
		assertTrue(NativeMoneroEngine.acceptableJniString(
				"socks5://user:pass@127.0.0.1:9050",
				NativeMoneroEngine.MAX_ENDPOINT_CHARS));
		assertTrue(NativeMoneroEngine.acceptableJniString("",
				NativeMoneroEngine.MAX_ADDRESS_CHARS));
		assertFalse(NativeMoneroEngine.acceptableJniString(null,
				NativeMoneroEngine.MAX_ADDRESS_CHARS));
		assertFalse(NativeMoneroEngine.acceptableJniString(ADDRESS + "\n",
				NativeMoneroEngine.MAX_ADDRESS_CHARS));
		assertFalse(NativeMoneroEngine.acceptableJniString(ADDRESS + "\0",
				NativeMoneroEngine.MAX_ADDRESS_CHARS));
		assertFalse(NativeMoneroEngine.acceptableJniString(ADDRESS + "é",
				NativeMoneroEngine.MAX_ADDRESS_CHARS));
		assertFalse(NativeMoneroEngine.acceptableJniString("😀",
				NativeMoneroEngine.MAX_ADDRESS_CHARS));
		StringBuilder huge = new StringBuilder();
		while (huge.length() <= 4096) huge.append(ADDRESS);
		assertFalse(NativeMoneroEngine.acceptableJniString(huge.toString(),
				NativeMoneroEngine.MAX_ADDRESS_CHARS));
		assertFalse(NativeMoneroEngine.acceptableJniString(huge.toString(),
				NativeMoneroEngine.MAX_ENDPOINT_CHARS));
	}

	@Test
	public void hostileAddressesAreRefusedBeforeNativeCode() {
		NativeMoneroEngine engine = new NativeMoneroEngine();
		StringBuilder huge = new StringBuilder();
		while (huge.length() <= 4096) huge.append(ADDRESS);
		assertFalse(engine.validateAddress(huge.toString()));
		assertFalse(engine.validateAddress(ADDRESS + "\0"));
		assertEquals(MoneroEngine.AddressKind.INVALID,
				engine.addressKind(huge.toString()));
		assertEquals(MoneroEngine.AddressKind.INVALID,
				engine.addressKind("é" + ADDRESS));
	}
}
