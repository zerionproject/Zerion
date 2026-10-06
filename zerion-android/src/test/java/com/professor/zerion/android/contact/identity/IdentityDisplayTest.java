package com.professor.zerion.android.contact.identity;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IdentityDisplayTest {

	private static byte[] bytes(int n, long seed) {
		byte[] b = new byte[n];
		new Random(seed).nextBytes(b);
		return b;
	}

	@Test
	public void bothVersionsAreShownAndMatchTheFingerprintFunctions() {
		byte[] ed = bytes(32, 1);
		byte[] mlDsa = bytes(1952, 2);
		String lines = IdentityDisplay.lines(ed, mlDsa, "v2 %s", "v1 %s");
		String[] parts = lines.split("\n");
		assertEquals(2, parts.length);
		assertEquals("v2 " + IdentityFingerprint.forIdentity(ed, mlDsa),
				parts[0]);
		assertEquals("v1 " + IdentityFingerprint.forSigningPub(ed), parts[1]);
		assertEquals(IdentityFingerprint.forIdentity(ed, mlDsa),
				IdentityDisplay.hybridFingerprint(ed, mlDsa));
	}

	@Test
	public void withoutTheMlDsaKeyOnlyVersionOneIsShown() {
		byte[] ed = bytes(32, 3);
		assertNull(IdentityDisplay.hybridFingerprint(ed, null));
		assertNull(IdentityDisplay.hybridFingerprint(ed, new byte[0]));
		String lines = IdentityDisplay.lines(ed, null, "v2 %s", "v1 %s");
		assertEquals("v1 " + IdentityFingerprint.forSigningPub(ed), lines);
		assertTrue(lines.indexOf('\n') < 0);
	}
}
