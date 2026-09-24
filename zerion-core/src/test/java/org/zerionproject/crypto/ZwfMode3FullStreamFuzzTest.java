package org.zerionproject.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.DhRatchetState;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Random;

import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Bounded adversarial coverage of the Mode 3-Full stream decrypter: every bit
 * of the stream start, sampled bits of every frame, every truncation boundary
 * and random input. The invariant is that the decrypter either returns the
 * sealed payload or throws an {@link IOException}; it never returns a
 * modified payload and never fails with any other exception.
 */
public class ZwfMode3FullStreamFuzzTest {

	private static final int START_LENGTH = TAG_LENGTH + STREAM_HEADER_LENGTH;
	private static final int BODY_FLIPS = 160;
	private static final int RANDOM_INPUTS = 200;

	private final Random random = new Random(0x5eed);
	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
		constructor.setAccessible(true);
		crypto = (CryptoComponent) constructor.newInstance(
				new TestSecureRandomProvider(), null);
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return System.currentTimeMillis();
			}

			@Override
			public void sleep(long ms) throws InterruptedException {
				Thread.sleep(ms);
			}
		};
		ratchet = new PcsRatchetImpl(crypto, clock);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> providerCtor = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		providerCtor.setAccessible(true);
		MlKemProvider mlKemProvider = (MlKemProvider) providerCtor
				.newInstance(crypto.getSecureRandom());
		Class<?> ratchetImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> ratchetCtor = ratchetImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		ratchetCtor.setAccessible(true);
		mode3FullRatchet = (Mode3FullRatchet) ratchetCtor.newInstance(
				crypto, mlKemProvider);
	}

	/** A sealed stream together with the receiver state that opens it. */
	private static final class Sealed {
		final byte[] tag;
		final SecretKey headerKey;
		final PcsSessionState receiver;
		final byte[] bytes;
		final byte[][] payloads;

		Sealed(byte[] tag, SecretKey headerKey, PcsSessionState receiver,
				byte[] bytes, byte[][] payloads) {
			this.tag = tag;
			this.headerKey = headerKey;
			this.receiver = receiver;
			this.bytes = bytes;
			this.payloads = payloads;
		}
	}

	@Test
	public void untamperedStreamOpens() throws Exception {
		Sealed s = seal(0, 1, 700, ZwfMode3FullStreamEncrypter.maxMessageLength());
		assertEquals(START_LENGTH + 4 * FRAME_LENGTH, s.bytes.length);
		ZwfMode3FullStreamDecrypter dec = opener(s, s.bytes);
		byte[] buf = new byte[FRAME_LENGTH];
		for (byte[] expected : s.payloads) {
			int n = dec.readFrame(buf);
			assertArrayEquals(expected, Arrays.copyOf(buf, n));
		}
	}

	@Test
	public void everyBitOfTheStreamStartIsAuthenticated() throws Exception {
		Sealed s = seal(64);
		for (int pos = 0; pos < START_LENGTH; pos++) {
			for (int bit = 0; bit < 8; bit++) {
				byte[] mutated = s.bytes.clone();
				mutated[pos] ^= (byte) (1 << bit);
				expectRefusal(s, mutated, 0, "start byte " + pos + " bit " + bit);
			}
		}
	}

	@Test
	public void sampledBitFlipsInEveryFrameAreRefused() throws Exception {
		int max = ZwfMode3FullStreamEncrypter.maxMessageLength();
		Sealed s = seal(0, 300, max / 2, max);
		int bodyLength = s.bytes.length - START_LENGTH;
		for (int i = 0; i < BODY_FLIPS; i++) {
			int pos = START_LENGTH + random.nextInt(bodyLength);
			int frame = (pos - START_LENGTH) / FRAME_LENGTH;
			byte[] mutated = s.bytes.clone();
			mutated[pos] ^= (byte) (1 << random.nextInt(8));
			expectRefusal(s, mutated, frame, "byte " + pos);
		}
	}

	@Test
	public void everyTruncationBoundaryIsRefusedWithoutAPayload()
			throws Exception {
		Sealed s = seal(10, 20);
		int[] lengths = {0, 1, TAG_LENGTH - 1, TAG_LENGTH, TAG_LENGTH + 1,
				START_LENGTH - 1, START_LENGTH, START_LENGTH + 1,
				START_LENGTH + FRAME_LENGTH - 1};
		for (int length : lengths) {
			expectRefusal(s, Arrays.copyOf(s.bytes, length), 0,
					"truncated to " + length);
		}
		ZwfMode3FullStreamDecrypter dec = opener(s,
				Arrays.copyOf(s.bytes, START_LENGTH + FRAME_LENGTH));
		byte[] buf = new byte[FRAME_LENGTH];
		assertEquals(10, dec.readFrame(buf));
		try {
			dec.readFrame(buf);
			fail("the second frame is missing");
		} catch (IOException expected) {
		}
	}

	@Test
	public void randomInputNeverOpens() throws Exception {
		Sealed s = seal(5);
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] junk = new byte[random.nextInt(START_LENGTH + 2 * FRAME_LENGTH)];
			random.nextBytes(junk);
			if (i % 4 == 0 && junk.length >= START_LENGTH) {
				System.arraycopy(s.bytes, 0, junk, 0, TAG_LENGTH);
			}
			if (i % 4 == 1 && junk.length >= START_LENGTH) {
				System.arraycopy(s.bytes, 0, junk, 0, START_LENGTH);
			}
			expectRefusal(s, junk, 0, "random input " + i);
		}
	}

	@Test
	public void payloadLengthsAcrossThePaddingRangeRoundTrip() throws Exception {
		int max = ZwfMode3FullStreamEncrypter.maxMessageLength();
		int[] lengths = {0, 1, 2, 15, 16, 17, 255, 256, 1023, 1024, max - 1, max};
		Sealed s = seal(lengths);
		ZwfMode3FullStreamDecrypter dec = opener(s, s.bytes);
		byte[] buf = new byte[FRAME_LENGTH];
		for (byte[] expected : s.payloads) {
			int n = dec.readFrame(buf);
			assertArrayEquals(expected, Arrays.copyOf(buf, n));
		}
	}

	@Test
	public void oversizedPayloadIsRefusedBySender() throws Exception {
		int max = ZwfMode3FullStreamEncrypter.maxMessageLength();
		SecretKey rootKey = randomKey();
		Pair p = hybridPair(rootKey);
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				new ByteArrayOutputStream(), cipher(), ratchet, mode3FullRatchet,
				1L, randomBytes(TAG_LENGTH), randomBytes(24), randomKey(),
				p.sender, null);
		try {
			enc.writeFrame(new byte[max + 1], max + 1, true);
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	/**
	 * Opens {@code bytes}; the frames before {@code failingFrame} must equal the
	 * sealed payloads and the failing frame must be refused with an
	 * {@link IOException}, never with anything else and never with a payload.
	 */
	private void expectRefusal(Sealed s, byte[] bytes, int failingFrame,
			String what) throws Exception {
		ZwfMode3FullStreamDecrypter dec = opener(s, bytes);
		byte[] buf = new byte[FRAME_LENGTH];
		for (int f = 0; f < failingFrame; f++) {
			int n = dec.readFrame(buf);
			assertArrayEquals(what, s.payloads[f], Arrays.copyOf(buf, n));
		}
		try {
			int n = dec.readFrame(buf);
			fail(what + ": opened with " + n + " bytes");
		} catch (IOException expected) {
		} catch (RuntimeException e) {
			throw new AssertionError(what + ": " + e, e);
		}
	}

	private Sealed seal(int... payloadLengths) throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey headerKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Pair p = hybridPair(rootKey);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), headerKey, p.sender, null);
		byte[][] payloads = new byte[payloadLengths.length][];
		for (int i = 0; i < payloadLengths.length; i++) {
			payloads[i] = randomBytes(payloadLengths[i]);
			enc.writeFrame(payloads[i], payloads[i].length,
					i == payloadLengths.length - 1);
		}
		assertTrue(out.size() > 0);
		return new Sealed(tag, headerKey, p.receiver, out.toByteArray(),
				payloads);
	}

	private ZwfMode3FullStreamDecrypter opener(Sealed s, byte[] bytes) {
		return new ZwfMode3FullStreamDecrypter(new ByteArrayInputStream(bytes),
				cipher(), ratchet, mode3FullRatchet, null, s.tag, 0L,
				s.headerKey, s.receiver, null);
	}

	private AuthenticatedCipher cipher() {
		return new XSalsa20Poly1305AuthenticatedCipher();
	}

	private SecretKey randomKey() {
		return new SecretKey(randomBytes(SecretKey.LENGTH));
	}

	private byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	private PcsSessionState stateWith(SecretKey rootKey, Mode3FullState m3f) {
		KeyPair dhKp = crypto.generateAgreementKeyPair();
		DhRatchetState dh = new DhRatchetState(dhKp, null);
		return PcsSessionState.createInitialMode3Full(rootKey, rootKey, dh, m3f);
	}

	private static final class Pair {
		final PcsSessionState sender;
		final PcsSessionState receiver;

		Pair(PcsSessionState sender, PcsSessionState receiver) {
			this.sender = sender;
			this.receiver = receiver;
		}
	}

	private Pair hybridPair(SecretKey rootKey) {
		Mode3FullState receiverM3f = mode3FullRatchet.createInitialState();
		Mode3FullState senderM3f = mode3FullRatchet.createInitialState()
				.withRecvAdvance(
						receiverM3f.getOurActiveKeyPair().getEncapsulationKey());
		return new Pair(stateWith(rootKey, senderM3f),
				stateWith(rootKey, receiverM3f));
	}
}
