package org.zerionproject.core.crypto;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.system.SystemClock;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AsyncPrekeyBundleTest {

	private final Random random = new Random(3);
	private CryptoComponent crypto;
	private KeyPair identitySig;
	private KeyPair identityAgree;

	@Before
	public void setUp() {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		identitySig = crypto.generateHybridSignatureKeyPair();
		identityAgree = crypto.generateHybridAgreementKeyPair();
	}

	private AsyncPrekeyBundle build(int oneTimeCount) throws Exception {
		KeyPair signedPrekey = crypto.generateHybridAgreementKeyPair();
		List<AsyncPrekeyBundle.OneTimePrekey> oneTime = new ArrayList<>();
		for (int i = 0; i < oneTimeCount; i++) {
			byte[] id = new byte[AsyncPrekeyBundle.ONE_TIME_PREKEY_ID_BYTES];
			random.nextBytes(id);
			oneTime.add(new AsyncPrekeyBundle.OneTimePrekey(id,
					crypto.generateHybridAgreementKeyPair().getPublic()
							.getEncoded()));
		}
		return AsyncPrekeyBundle.create(crypto,
				identitySig.getPublic().getEncoded(), identitySig.getPrivate(),
				identityAgree.getPublic().getEncoded(), 3L,
				signedPrekey.getPublic().getEncoded(), 9999999999L, oneTime);
	}

	@Test
	public void createdBundleVerifies() throws Exception {
		assertTrue(build(5).verify(crypto));
	}

	@Test
	public void encodeDecodeRoundTripsAndVerifies() throws Exception {
		AsyncPrekeyBundle b = build(3);
		AsyncPrekeyBundle d = AsyncPrekeyBundle.decode(b.encode());
		assertEquals(b.getSignedPrekeyId(), d.getSignedPrekeyId());
		assertEquals(b.getOneTimePrekeys().size(),
				d.getOneTimePrekeys().size());
		assertTrue(d.verify(crypto));
	}

	@Test
	public void emptyOneTimePoolVerifies() throws Exception {
		AsyncPrekeyBundle d = AsyncPrekeyBundle.decode(build(0).encode());
		assertTrue(d.verify(crypto));
		assertEquals(0, d.getOneTimePrekeys().size());
	}

	@Test
	public void tamperedSignedPrekeyFailsVerify() throws Exception {
		byte[] enc = build(2).encode();
		// Flip a byte inside signedPrekeyPub (after version+identity keys).
		int off = 1 + 1984 + 1216 + 4 + 10;
		enc[off] ^= 0x01;
		AsyncPrekeyBundle d = AsyncPrekeyBundle.decode(enc);
		assertFalse(d.verify(crypto));
	}

	@Test
	public void wrongIdentityFailsVerify() throws Exception {
		AsyncPrekeyBundle b = build(1);
		CryptoComponent other = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		// Same crypto engine, but re-sign nothing: a bundle whose identity key
		// does not match its signatures must fail. Rebuild with a mismatched
		// identity by decoding then verifying against a bundle we tamper.
		byte[] enc = b.encode();
		enc[1] ^= 0x01; // corrupt the identity sig pub
		AsyncPrekeyBundle d = AsyncPrekeyBundle.decode(enc);
		assertFalse(d.verify(crypto));
		assertFalse(d.verify(other));
	}

	@Test
	public void decodeRejectsBadVersion() throws Exception {
		byte[] enc = build(1).encode();
		enc[0] = 0x02;
		try {
			AsyncPrekeyBundle.decode(enc);
			fail("expected FormatException");
		} catch (FormatException expected) {
			// ok
		}
	}
}
