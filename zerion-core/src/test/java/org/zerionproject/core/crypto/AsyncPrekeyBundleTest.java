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

import static org.junit.Assert.assertArrayEquals;
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

	private List<AsyncPrekeyBundle.OneTimePrekey> oneTimePrekeys(int count) {
		List<AsyncPrekeyBundle.OneTimePrekey> oneTime = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			byte[] id = new byte[AsyncPrekeyBundle.ONE_TIME_PREKEY_ID_BYTES];
			random.nextBytes(id);
			oneTime.add(new AsyncPrekeyBundle.OneTimePrekey(id,
					crypto.generateHybridAgreementKeyPair().getPublic()
							.getEncoded()));
		}
		return oneTime;
	}

	private AsyncPrekeyBundle build(int oneTimeCount) throws Exception {
		KeyPair signedPrekey = crypto.generateHybridAgreementKeyPair();
		return AsyncPrekeyBundle.create(crypto,
				identitySig.getPublic().getEncoded(), identitySig.getPrivate(),
				identityAgree.getPublic().getEncoded(), 3L,
				signedPrekey.getPublic().getEncoded(), 9999999999L,
				oneTimePrekeys(oneTimeCount));
	}

	private AsyncPrekeyBundle buildUnsigned(int oneTimeCount) {
		KeyPair signedPrekey = crypto.generateHybridAgreementKeyPair();
		return AsyncPrekeyBundle.createUnsigned(
				identitySig.getPublic().getEncoded(),
				identityAgree.getPublic().getEncoded(), 3L,
				signedPrekey.getPublic().getEncoded(), 9999999999L,
				oneTimePrekeys(oneTimeCount));
	}

	@Test
	public void unsignedBundleRoundTripsWithoutSignatureFields()
			throws Exception {
		AsyncPrekeyBundle b = buildUnsigned(3);
		byte[] enc = b.encode();
		assertEquals(AsyncPrekeyBundle.VERSION_UNSIGNED, enc[0] & 0xFF);
		assertEquals(build(3).encode().length - 2 * 3373, enc.length);
		AsyncPrekeyBundle d = AsyncPrekeyBundle.decode(enc);
		assertFalse(d.isSigned());
		assertEquals(AsyncPrekeyBundle.VERSION_UNSIGNED, d.getVersion());
		assertEquals(3L, d.getSignedPrekeyId());
		assertEquals(9999999999L, d.getSignedPrekeyExpiry());
		assertEquals(3, d.getOneTimePrekeys().size());
		assertArrayEquals(b.getIdentitySigPub(), d.getIdentitySigPub());
		assertArrayEquals(b.getIdentityAgreePub(), d.getIdentityAgreePub());
		assertArrayEquals(b.getSignedPrekeyPub(), d.getSignedPrekeyPub());
		for (int i = 0; i < 3; i++) {
			assertArrayEquals(b.getOneTimePrekeys().get(i).id,
					d.getOneTimePrekeys().get(i).id);
			assertArrayEquals(b.getOneTimePrekeys().get(i).pub,
					d.getOneTimePrekeys().get(i).pub);
		}
	}

	@Test
	public void unsignedBundleIsAcceptedOnlyFromItsOwnContact()
			throws Exception {
		AsyncPrekeyBundle d = AsyncPrekeyBundle.decode(buildUnsigned(2)
				.encode());
		assertFalse(d.verify(crypto));
		assertTrue(d.verifyFromContact(crypto,
				identitySig.getPublic().getEncoded()));
		KeyPair other = crypto.generateHybridSignatureKeyPair();
		assertFalse(d.verifyFromContact(crypto,
				other.getPublic().getEncoded()));
	}

	@Test
	public void signedBundleIsAcceptedFromItsOwnContactOnly()
			throws Exception {
		AsyncPrekeyBundle d = AsyncPrekeyBundle.decode(build(2).encode());
		assertTrue(d.isSigned());
		assertEquals(AsyncPrekeyBundle.VERSION, d.getVersion());
		assertTrue(d.verifyFromContact(crypto,
				identitySig.getPublic().getEncoded()));
		KeyPair other = crypto.generateHybridSignatureKeyPair();
		assertFalse(d.verifyFromContact(crypto,
				other.getPublic().getEncoded()));
		byte[] enc = build(2).encode();
		enc[enc.length - 1] ^= 0x01;
		assertFalse(AsyncPrekeyBundle.decode(enc).verifyFromContact(crypto,
				identitySig.getPublic().getEncoded()));
	}

	@Test
	public void unsignedBundleWithTrailingBytesIsRefused() throws Exception {
		byte[] enc = buildUnsigned(1).encode();
		byte[] longer = java.util.Arrays.copyOf(enc, enc.length + 1);
		try {
			AsyncPrekeyBundle.decode(longer);
			fail("expected FormatException");
		} catch (FormatException expected) {
		}
		try {
			AsyncPrekeyBundle.decode(java.util.Arrays.copyOf(enc,
					enc.length - 1));
			fail("expected FormatException");
		} catch (FormatException expected) {
		}
	}

	@Test
	public void signedBodyUnderTheUnsignedVersionByteIsRefused()
			throws Exception {
		byte[] enc = build(1).encode();
		enc[0] = (byte) AsyncPrekeyBundle.VERSION_UNSIGNED;
		try {
			AsyncPrekeyBundle.decode(enc);
			fail("expected FormatException");
		} catch (FormatException expected) {
		}
		byte[] unsigned = buildUnsigned(1).encode();
		unsigned[0] = (byte) AsyncPrekeyBundle.VERSION;
		try {
			AsyncPrekeyBundle.decode(unsigned);
			fail("expected FormatException");
		} catch (FormatException expected) {
		}
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
		byte[] enc = b.encode();
		enc[1] ^= 0x01;
		AsyncPrekeyBundle d = AsyncPrekeyBundle.decode(enc);
		assertFalse(d.verify(crypto));
		assertFalse(d.verify(other));
	}

	@Test
	public void decodeRejectsBadVersion() throws Exception {
		byte[] enc = build(1).encode();
		enc[0] = 0x03;
		try {
			AsyncPrekeyBundle.decode(enc);
			fail("expected FormatException");
		} catch (FormatException expected) {
		}
	}
}
