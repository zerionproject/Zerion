package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.cert.CertificateException;

public class TlsTrustTest {

	private static final byte[] CERT = "a fake certificate body".getBytes();

	@Test
	public void sha256HexKnownVector() {
		assertEquals(
				"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
				TlsTrust.sha256Hex("abc".getBytes()));
	}

	@Test
	public void matchingPinAcceptsEvenWithoutCa() throws CertificateException {
		String pin = TlsTrust.sha256Hex(CERT);
		TlsTrust.verify(pin, CERT, false, false);
	}

	@Test
	public void pinMismatchIsHardFailure() {
		String wrong =
				"0000000000000000000000000000000000000000000000000000000000000000";
		assertThrows(CertificateException.class,
				() -> TlsTrust.verify(wrong, CERT, true, true));
	}

	@Test
	public void noPinRequiresCaAndHostname() throws CertificateException {
		TlsTrust.verify(null, CERT, true, true);
	}

	@Test
	public void noPinUntrustedCaRejected() {
		assertThrows(CertificateException.class,
				() -> TlsTrust.verify(null, CERT, false, true));
	}

	@Test
	public void noPinHostnameMismatchRejected() {
		assertThrows(CertificateException.class,
				() -> TlsTrust.verify(null, CERT, true, false));
	}

	@Test
	public void emptyPinTreatedAsNoPin() {
		assertThrows(CertificateException.class,
				() -> TlsTrust.verify("", CERT, false, true));
	}

	@Test
	public void constEq() {
		assertTrue(TlsTrust.constEq("abcd", "abcd"));
		assertFalse(TlsTrust.constEq("abcd", "abce"));
		assertFalse(TlsTrust.constEq("abc", "abcd"));
	}

	@Test
	public void pinnedFactoryBuilds() throws Exception {
		assertNotNull(TlsTrust.pinnedFactory(
				"ab".repeat(32)));
	}
}
