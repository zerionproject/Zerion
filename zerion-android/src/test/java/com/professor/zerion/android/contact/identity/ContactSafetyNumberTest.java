package com.professor.zerion.android.contact.identity;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ContactSafetyNumberTest {

	private final Random random = new Random(3);

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	@Test
	public void aSubstitutedMlDsaKeyChangesTheSafetyNumber() {
		byte[] localEd = bytes(32);
		byte[] localMl = bytes(1952);
		byte[] remoteEd = bytes(32);
		byte[] realMl = bytes(1952);
		byte[] forgedMl = bytes(1952);
		String real = ContactSafetyNumber.forKeys(localEd, localMl, remoteEd,
				realMl);
		String forged = ContactSafetyNumber.forKeys(localEd, localMl,
				remoteEd, forgedMl);
		assertNotEquals(real, forged);
		assertEquals(ContactSafetyNumber.VERSION_HYBRID,
				ContactSafetyNumber.versionFor(localMl, realMl));
	}

	@Test
	public void bothSidesComputeTheSameNumber() {
		byte[] aEd = bytes(32);
		byte[] aMl = bytes(1952);
		byte[] bEd = bytes(32);
		byte[] bMl = bytes(1952);
		assertEquals(ContactSafetyNumber.forKeys(aEd, aMl, bEd, bMl),
				ContactSafetyNumber.forKeys(bEd, bMl, aEd, aMl));
	}

	@Test
	public void anUnknownMlDsaKeyFallsBackToVersionOne() {
		byte[] aEd = bytes(32);
		byte[] bEd = bytes(32);
		assertEquals(ContactSafetyNumber.VERSION_ED25519_ONLY,
				ContactSafetyNumber.versionFor(bytes(1952), null));
		assertEquals(ContactSafetyNumber.forKeys(aEd, bEd),
				ContactSafetyNumber.forKeys(aEd, bytes(1952), bEd, null));
	}

	@Test
	public void theFingerprintCoversTheMlDsaKey() {
		byte[] ed = bytes(32);
		assertNotEquals(IdentityFingerprint.forIdentity(ed, bytes(1952)),
				IdentityFingerprint.forIdentity(ed, bytes(1952)));
	}
}
