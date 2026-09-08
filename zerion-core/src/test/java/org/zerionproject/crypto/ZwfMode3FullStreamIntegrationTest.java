package org.zerionproject.crypto;

import org.zerionproject.core.api.FormatException;
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
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Real-crypto round trip for the re-anchored Mode 3-Full stream: a genuine
 * Mode 3-Full session (real ML-KEM keys, real ratchet) is sealed through
 * {@link ZwfMode3FullStreamEncrypter} and opened through
 * {@link ZwfMode3FullStreamDecrypter}. Proves the hybrid post-quantum body key
 * actually derives on both sides, the fixed 4 KB framing is exact, and
 * tampering / wrong tag are rejected.
 */
public class ZwfMode3FullStreamIntegrationTest {

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

	private AuthenticatedCipher cipher() {
		return new XSalsa20Poly1305AuthenticatedCipher();
	}

	private SecretKey randomKey() {
		byte[] k = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(k);
		return new SecretKey(k);
	}

	private byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		crypto.getSecureRandom().nextBytes(b);
		return b;
	}

	private PcsSessionState stateWith(SecretKey rootKey, Mode3FullState m3f) {
		KeyPair dhKp = crypto.generateAgreementKeyPair();
		DhRatchetState dh = new DhRatchetState(dhKp, null);
		return PcsSessionState.createInitialMode3Full(rootKey, rootKey, dh, m3f);
	}

	/**
	 * Sets up a sender whose active peer PQ key is the receiver's real key, so
	 * every frame exercises the true ML-KEM encapsulate/decapsulate path (not
	 * the zero-ciphertext sentinel).
	 */
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

	@Test
	public void singleFrameHybridRoundTrip() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		byte[] streamHeaderNonce = randomBytes(24);
		Pair p = hybridPair(rootKey);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				streamHeaderNonce, streamHeaderKey, p.sender, null);

		byte[] msg = "hello zerion 3.0 mode3full".getBytes();
		enc.writeFrame(msg, msg.length, true);

		// tag + native stream header + exactly one 4 KB frame
		assertEquals(TAG_LENGTH + STREAM_HEADER_LENGTH + FRAME_LENGTH,
				out.toByteArray().length);

		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(out.toByteArray()), cipher(), ratchet,
				mode3FullRatchet, null, tag, 0L, streamHeaderKey, p.receiver, null);

		byte[] buf = new byte[FRAME_LENGTH];
		int n = dec.readFrame(buf);
		assertTrue("expected payload, got " + n, n > 0);
		assertArrayEquals(msg, Arrays.copyOf(buf, n));
		assertEquals(1L, dec.getStreamId());
		assertEquals(-1, dec.readFrame(buf)); // final frame consumed
	}

	@Test
	public void manyFramesHybridRoundTrip() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		byte[] streamHeaderNonce = randomBytes(24);
		Pair p = hybridPair(rootKey);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 7L, tag,
				streamHeaderNonce, streamHeaderKey, p.sender, null);

		int frames = 12;
		String[] sent = new String[frames];
		for (int i = 0; i < frames; i++) {
			sent[i] = "message-" + i + "-with-some-payload-bytes";
			byte[] m = sent[i].getBytes();
			enc.writeFrame(m, m.length, i == frames - 1);
		}
		assertEquals(TAG_LENGTH + STREAM_HEADER_LENGTH + frames * FRAME_LENGTH,
				out.toByteArray().length);

		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(out.toByteArray()), cipher(), ratchet,
				mode3FullRatchet, null, tag, 0L, streamHeaderKey, p.receiver, null);

		byte[] buf = new byte[FRAME_LENGTH];
		for (int i = 0; i < frames; i++) {
			int n = dec.readFrame(buf);
			assertTrue("frame " + i + " length " + n, n > 0);
			assertEquals("frame " + i, sent[i],
					new String(Arrays.copyOf(buf, n)));
		}
		assertEquals(7L, dec.getStreamId());
		assertEquals(-1, dec.readFrame(buf));
	}

	@Test
	public void emptyPayloadRoundTrips() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Pair p = hybridPair(rootKey);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), streamHeaderKey, p.sender, null);
		enc.writeFrame(new byte[0], 0, true);
		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(out.toByteArray()), cipher(), ratchet,
				mode3FullRatchet, null, tag, 0L, streamHeaderKey, p.receiver, null);
		int n = dec.readFrame(new byte[FRAME_LENGTH]);
		assertEquals(0, n);
	}

	@Test
	public void tamperedFrameIsRejected() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Pair p = hybridPair(rootKey);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), streamHeaderKey, p.sender, null);
		enc.writeFrame("tamper me".getBytes(), 9, true);
		byte[] bytes = out.toByteArray();
		// flip a byte inside the body segment of the frame
		bytes[bytes.length - 100] ^= 0x01;
		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(bytes), cipher(), ratchet,
				mode3FullRatchet, null, tag, 0L, streamHeaderKey, p.receiver, null);
		try {
			dec.readFrame(new byte[FRAME_LENGTH]);
			fail("expected FormatException on tampered frame");
		} catch (FormatException expected) {
			// good
		}
	}

	@Test
	public void wrongTagIsRejected() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Pair p = hybridPair(rootKey);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), streamHeaderKey, p.sender, null);
		enc.writeFrame("hi".getBytes(), 2, true);
		byte[] wrongTag = randomBytes(TAG_LENGTH);
		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(out.toByteArray()), cipher(), ratchet,
				mode3FullRatchet, null, wrongTag, 0L, streamHeaderKey, p.receiver,
				null);
		try {
			dec.readFrame(new byte[FRAME_LENGTH]);
			fail("expected FormatException on wrong tag");
		} catch (FormatException expected) {
			// good
		}
	}

	@Test
	public void headerStreamIdMustMatchExpected() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Pair p = hybridPair(rootKey);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), streamHeaderKey, p.sender, null);
		enc.writeFrame("hi".getBytes(), 2, true);
		// The header carries streamId 1, but we tell the decrypter to expect 2
		// (as if the replay-checked tag id disagreed with the header id).
		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(out.toByteArray()), cipher(), ratchet,
				mode3FullRatchet, null, tag, 2L, streamHeaderKey, p.receiver,
				null);
		try {
			dec.readFrame(new byte[FRAME_LENGTH]);
			fail("expected FormatException on stream-id mismatch");
		} catch (FormatException expected) {
			// good
		}
	}

	@Test
	public void wrongStreamHeaderKeyIsRejected() throws Exception {
		SecretKey rootKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Pair p = hybridPair(rootKey);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), randomKey(), p.sender, null);
		enc.writeFrame("hi".getBytes(), 2, true);
		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(out.toByteArray()), cipher(), ratchet,
				mode3FullRatchet, null, tag, 0L, randomKey() /* wrong */, p.receiver,
				null);
		try {
			dec.readFrame(new byte[FRAME_LENGTH]);
			fail("expected FormatException on wrong stream header key");
		} catch (FormatException expected) {
			// good
		}
	}

	/**
	 * Once the receive direction has accepted a frame carrying a real ML-KEM
	 * contribution, a later frame with the all-zero sentinel ciphertext must be
	 * rejected: an attacker holding the symmetric chain state must not be able
	 * to keep the receiver on a branch that never absorbs fresh PQ entropy.
	 */
	@Test
	public void postBootstrapZeroKemCiphertextIsRejected() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Mode3FullState receiverM3f = mode3FullRatchet.createInitialState();
		Mode3FullState senderWithKey = mode3FullRatchet.createInitialState()
				.withRecvAdvance(
						receiverM3f.getOurActiveKeyPair().getEncapsulationKey());
		AtomicReference<Mode3FullState> injected = new AtomicReference<>();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), streamHeaderKey,
				stateWith(rootKey, senderWithKey), null,
				injected::get, null, null, true);
		enc.writeFrame("real pq frame".getBytes(), 13, false);
		injected.set(mode3FullRatchet.createInitialState());
		enc.writeFrame("sentinel frame".getBytes(), 14, true);

		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(out.toByteArray()), cipher(), ratchet,
				mode3FullRatchet, null, tag, 0L, streamHeaderKey,
				stateWith(rootKey, receiverM3f), null);
		byte[] buf = new byte[FRAME_LENGTH];
		assertTrue(dec.readFrame(buf) > 0);
		try {
			dec.readFrame(buf);
			fail("expected FormatException on post-bootstrap zero sentinel");
		} catch (FormatException expected) {
			// good
		}
	}

	/**
	 * The sentinel is legitimate only during bootstrap: it is accepted before
	 * the first real ML-KEM contribution, and permanently rejected afterwards.
	 */
	@Test
	public void pqConfirmationIsMonotonicPerReceiveDirection() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Mode3FullState receiverM3f = mode3FullRatchet.createInitialState();
		Mode3FullState senderNoKey = mode3FullRatchet.createInitialState();
		Mode3FullState senderWithKey = senderNoKey.withRecvAdvance(
				receiverM3f.getOurActiveKeyPair().getEncapsulationKey());
		AtomicReference<Mode3FullState> injected = new AtomicReference<>();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), streamHeaderKey,
				stateWith(rootKey, senderNoKey), null,
				injected::get, null, null, true);
		enc.writeFrame("bootstrap sentinel".getBytes(), 18, false);
		injected.set(senderWithKey);
		enc.writeFrame("first real pq".getBytes(), 13, false);
		injected.set(mode3FullRatchet.createInitialState());
		enc.writeFrame("late sentinel".getBytes(), 13, true);

		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(out.toByteArray()), cipher(), ratchet,
				mode3FullRatchet, null, tag, 0L, streamHeaderKey,
				stateWith(rootKey, receiverM3f), null);
		byte[] buf = new byte[FRAME_LENGTH];
		assertTrue("bootstrap sentinel accepted", dec.readFrame(buf) > 0);
		assertTrue("first real PQ frame accepted", dec.readFrame(buf) > 0);
		try {
			dec.readFrame(buf);
			fail("expected FormatException on sentinel after PQ confirmation");
		} catch (FormatException expected) {
			// good
		}
	}

	/**
	 * A frame whose body fails authentication must not publish the candidate
	 * Mode 3-Full state to the shared cell: only fully committed frames may
	 * advance what the send side and close-time persistence can observe.
	 */
	@Test
	public void rejectedFrameDoesNotPublishMode3FullState() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		byte[] tag = randomBytes(TAG_LENGTH);
		Pair p = hybridPair(rootKey);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfMode3FullStreamEncrypter enc = new ZwfMode3FullStreamEncrypter(
				out, cipher(), ratchet, mode3FullRatchet, 1L, tag,
				randomBytes(24), streamHeaderKey, p.sender, null);
		enc.writeFrame("good frame".getBytes(), 10, false);
		enc.writeFrame("bad frame".getBytes(), 9, true);
		byte[] bytes = out.toByteArray();
		bytes[bytes.length - 100] ^= 0x01;

		List<Mode3FullState> published = new ArrayList<>();
		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(bytes), cipher(), ratchet,
				mode3FullRatchet, null, tag, 0L, streamHeaderKey, p.receiver,
				null, null, published::add, null, true);
		byte[] buf = new byte[FRAME_LENGTH];
		assertTrue(dec.readFrame(buf) > 0);
		assertEquals("the accepted frame publishes exactly once", 1,
				published.size());
		try {
			dec.readFrame(buf);
			fail("expected FormatException on tampered body");
		} catch (FormatException expected) {
			// good
		}
		assertEquals("the rejected frame must not publish state", 1,
				published.size());
	}
}
