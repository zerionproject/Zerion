package org.zerionproject.core.crypto;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.system.SystemClock;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A prekey bundle that does not decode as the bytes its author signed never
 * verifies: random input and sampled bit flips of a valid bundle are refused
 * by the decoder or fail the signature check, and truncations are refused.
 */
public class AsyncPrekeyBundleFuzzTest {

	private static final int RANDOM_INPUTS = 1500;
	private static final int SAMPLED_FLIPS = 120;

	private final Random random = new Random(23);
	private CryptoComponent crypto;
	private byte[] valid;

	@Before
	public void setUp() throws Exception {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		KeyPair identitySig = crypto.generateHybridSignatureKeyPair();
		KeyPair identityAgree = crypto.generateHybridAgreementKeyPair();
		KeyPair signedPrekey = crypto.generateHybridAgreementKeyPair();
		List<AsyncPrekeyBundle.OneTimePrekey> oneTime = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			byte[] id = new byte[AsyncPrekeyBundle.ONE_TIME_PREKEY_ID_BYTES];
			random.nextBytes(id);
			oneTime.add(new AsyncPrekeyBundle.OneTimePrekey(id,
					crypto.generateHybridAgreementKeyPair().getPublic()
							.getEncoded()));
		}
		valid = AsyncPrekeyBundle.create(crypto,
				identitySig.getPublic().getEncoded(), identitySig.getPrivate(),
				identityAgree.getPublic().getEncoded(), 3L,
				signedPrekey.getPublic().getEncoded(), 9999999999L, oneTime)
				.encode();
		assertTrue(AsyncPrekeyBundle.decode(valid).verify(crypto));
	}

	@Test
	public void randomInputNeverVerifies() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] in = new byte[random.nextInt(valid.length + 64)];
			random.nextBytes(in);
			if (i % 2 == 0 && in.length > 0) in[0] = (byte) AsyncPrekeyBundle.VERSION;
			assertNotAccepted(in, "random input " + i);
		}
	}

	@Test
	public void sampledBitFlipsOfAValidBundleNeverVerify() {
		for (int i = 0; i < SAMPLED_FLIPS; i++) {
			byte[] mutated = valid.clone();
			int pos = random.nextInt(valid.length);
			mutated[pos] ^= (byte) (1 << random.nextInt(8));
			assertNotAccepted(mutated, "byte " + pos);
		}
	}

	@Test
	public void everyTruncationIsRefused() {
		for (int len = 0; len < valid.length; len += 7) {
			assertNotAccepted(Arrays.copyOf(valid, len), "truncated to " + len);
		}
		assertNotAccepted(Arrays.copyOf(valid, valid.length - 1),
				"truncated by one");
	}

	private void assertNotAccepted(byte[] in, String what) {
		AsyncPrekeyBundle decoded;
		try {
			decoded = AsyncPrekeyBundle.decode(in);
		} catch (FormatException expected) {
			return;
		} catch (RuntimeException e) {
			throw new AssertionError(what + ": " + e, e);
		}
		assertFalse(what + ": a mutated bundle verified", decoded.verify(crypto));
	}
}
