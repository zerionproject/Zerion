package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.KpId;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.crypto.ZwfMode3FullStreamDecrypter;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_SEND_ROTATION_INTERVAL;
import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

public class Mode3FullHealingAcrossConnectionsTest {

	private static final int AFTER_ROTATION = MODE3_FULL_SEND_ROTATION_INTERVAL + 1;

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;
	private ZwfSessionFactory sessionFactory;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		ratchet = new PcsRatchetImpl(crypto);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> providerCtor = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		providerCtor.setAccessible(true);
		MlKemProvider mlKem = (MlKemProvider) providerCtor.newInstance(
				crypto.getSecureRandom());
		Class<?> ratchetImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> ratchetCtor = ratchetImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		ratchetCtor.setAccessible(true);
		mode3FullRatchet = (Mode3FullRatchet) ratchetCtor.newInstance(crypto,
				mlKem);
		sessionFactory = new ZwfSessionFactory(crypto, mode3FullRatchet);
	}

	private static class MemStore implements StreamCounterStore {
		private final Map<Long, Long> m = new HashMap<>();

		@Override
		public synchronized long loadHighWater(int c, int d) {
			Long v = m.get((((long) c) << 1) | (d & 1L));
			return v == null ? 0 : v;
		}

		@Override
		public synchronized void storeHighWater(int c, int d, long hw) {
			m.put((((long) c) << 1) | (d & 1L), hw);
		}
	}

	private static final class Link {
		final ZwfDuplexConnection alice;
		final ZwfDuplexConnection bob;

		Link(ZwfDuplexConnection alice, ZwfDuplexConnection bob) {
			this.alice = alice;
			this.bob = bob;
		}

		void close() {
			alice.destroyKeyMaterial();
			bob.destroyKeyMaterial();
			alice.closeStreams();
			bob.closeStreams();
		}
	}

	private enum Fault { DROP, REPEAT, SWAP }

	private static final class FaultyFrames extends FilterOutputStream {
		private final Fault fault;
		private final int index;
		private int frames;
		private byte[] held;

		FaultyFrames(OutputStream out, Fault fault, int index) {
			super(out);
			this.fault = fault;
			this.index = index;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			if (len != FRAME_LENGTH) {
				out.write(b, off, len);
				return;
			}
			int n = frames++;
			if (n == index && fault == Fault.DROP) return;
			if (n == index && fault == Fault.SWAP) {
				held = Arrays.copyOfRange(b, off, off + len);
				return;
			}
			out.write(b, off, len);
			if (n == index && fault == Fault.REPEAT) out.write(b, off, len);
			if (n == index + 1 && held != null) {
				out.write(held);
				held = null;
			}
		}

		@Override
		public void write(byte[] b) throws IOException {
			write(b, 0, b.length);
		}
	}

	private static final class Tee extends FilterOutputStream {
		final ByteArrayOutputStream copy = new ByteArrayOutputStream();

		Tee(OutputStream out) {
			super(out);
		}

		@Override
		public void write(int b) throws IOException {
			out.write(b);
			copy.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
			copy.write(b, off, len);
		}
	}

	private Supplier<AuthenticatedCipher> cipherFactory() {
		return XSalsa20Poly1305AuthenticatedCipher::new;
	}

	private SecretKey randomRoot() {
		byte[] b = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(b);
		return new SecretKey(b);
	}

	private Link connect(SecretKey root, ZwfStreamCounter aliceCounter,
			ZwfStreamCounter bobCounter) throws IOException {
		return connect(root, aliceCounter, bobCounter, o -> o);
	}

	private Link connect(SecretKey root, ZwfStreamCounter aliceCounter,
			ZwfStreamCounter bobCounter,
			Function<OutputStream, OutputStream> aliceWire)
			throws IOException {
		ZwfSession aliceSession = sessionFactory.deriveSession(root, true);
		ZwfSession bobSession = sessionFactory.deriveSession(root, false);
		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);
		ZwfDuplexConnection alice = new ZwfDuplexConnection(1, aliceSession,
				aliceCounter, crypto, ratchet, mode3FullRatchet,
				cipherFactory(), aIn, aliceWire.apply(aOut));
		ZwfDuplexConnection bob = new ZwfDuplexConnection(2, bobSession,
				bobCounter, crypto, ratchet, mode3FullRatchet,
				cipherFactory(), bIn, bOut);
		return new Link(alice, bob);
	}

	private Link faultyLink(Fault fault) throws IOException {
		return connect(randomRoot(), new ZwfStreamCounter(new MemStore()),
				new ZwfStreamCounter(new MemStore()),
				o -> new FaultyFrames(o, fault, AFTER_ROTATION));
	}

	private static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static void aliceToBob(Link l, String text) throws IOException {
		l.alice.sendMessage(utf8(text));
		assertArrayEquals(utf8(text), l.bob.receiveMessage());
	}

	private static void bobToAlice(Link l, String text) throws IOException {
		l.bob.sendMessage(utf8(text));
		assertArrayEquals(utf8(text), l.alice.receiveMessage());
	}

	private static KpId activeId(ZwfDuplexConnection c) {
		return KpId.of(c.currentMode3FullState().getOurActiveKeyPair()
				.getEncapsulationKey());
	}

	private static void runPastAliceRotation(Link l) throws IOException {
		KpId aliceStart = activeId(l.alice);
		for (int i = 0; i < AFTER_ROTATION; i++) {
			aliceToBob(l, "a" + i);
			bobToAlice(l, "b" + i);
		}
		assertFalse("alice rotated before the faulty frame",
				aliceStart.equals(activeId(l.alice)));
	}

	private static void assertNextFrameRefused(Link l) {
		try {
			l.bob.receiveMessage();
			fail("a frame out of stream order must not open");
		} catch (IOException expected) {
		}
	}

	private ZwfMode3FullStreamDecrypter rootHolderReading(byte[] recorded,
			SecretKey root, MlKemKeyPair keyPair) {
		ZwfSession view = sessionFactory.deriveSession(root, false);
		Mode3FullState m3f = new Mode3FullState(null, keyPair,
				new LinkedHashMap<>(), 0);
		PcsSessionState state = view.getRecvState().withMode3FullState(m3f);
		byte[] tag = Arrays.copyOf(recorded, TAG_LENGTH);
		return new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(recorded), cipherFactory().get(),
				ratchet, mode3FullRatchet, tag, 0, view.getRecvHeaderKey(),
				state, null, null, null, null, true);
	}

	private static String readText(ZwfMode3FullStreamDecrypter d)
			throws IOException {
		byte[] buf = new byte[FRAME_LENGTH];
		int n = d.readFrame(buf);
		return new String(buf, 0, n, StandardCharsets.UTF_8);
	}

	@Test(timeout = 60_000)
	public void shortConnectionsEachStartWithAKeyPairNoEarlierConnectionHeld()
			throws Exception {
		SecretKey root = randomRoot();
		ZwfStreamCounter aliceCounter = new ZwfStreamCounter(new MemStore());
		ZwfStreamCounter bobCounter = new ZwfStreamCounter(new MemStore());
		int perConnection = MODE3_FULL_SEND_ROTATION_INTERVAL - 1;
		int connections = 4;
		Set<KpId> bobKeys = new HashSet<>();
		Set<KpId> aliceKeys = new HashSet<>();
		int bobFrames = 0;
		for (int c = 0; c < connections; c++) {
			Link l = connect(root, aliceCounter, bobCounter);
			KpId bobStart = activeId(l.bob);
			assertTrue("connection " + c + " reused a key pair",
					bobKeys.add(bobStart));
			assertTrue(aliceKeys.add(activeId(l.alice)));
			for (int i = 0; i < perConnection; i++) {
				aliceToBob(l, "a" + c + "-" + i);
				bobToAlice(l, "b" + c + "-" + i);
				bobFrames++;
			}
			assertEquals("fewer own frames than the interval never rotate",
					bobStart, activeId(l.bob));
			assertArrayEquals(
					l.bob.currentMode3FullState().getOurActiveKeyPair()
							.getEncapsulationKey(),
					l.alice.currentMode3FullState().getTheirActivePqPk());
			aliceToBob(l, "last " + c);
			assertTrue(l.bob.lastFrameCarriedPqSecret());
			assertEquals(bobStart,
					l.bob.currentMode3FullState().getPeerUsedKpId());
			l.close();
		}
		assertTrue(bobFrames > MODE3_FULL_SEND_ROTATION_INTERVAL * 3);
		assertEquals(connections, bobKeys.size());
		assertEquals(connections, aliceKeys.size());
	}

	@Test(timeout = 60_000)
	public void aKeyCopiedFromAnEarlierConnectionDoesNotOpenTheNextOne()
			throws Exception {
		SecretKey root = randomRoot();
		ZwfStreamCounter aliceCounter = new ZwfStreamCounter(new MemStore());
		ZwfStreamCounter bobCounter = new ZwfStreamCounter(new MemStore());
		Link first = connect(root, aliceCounter, bobCounter);
		aliceToBob(first, "one");
		bobToAlice(first, "two");
		aliceToBob(first, "three");
		MlKemKeyPair firstKey = first.bob.currentMode3FullState()
				.getOurActiveKeyPair();
		byte[] copiedDk = firstKey.getDecapsulationKey().clone();
		byte[] ekSeed = firstKey.getEkSeed().clone();
		byte[] ekVector = firstKey.getEkVector().clone();
		first.close();

		Tee[] wire = new Tee[1];
		Link second = connect(root, aliceCounter, bobCounter, o -> {
			wire[0] = new Tee(o);
			return wire[0];
		});
		aliceToBob(second, "four");
		bobToAlice(second, "five");
		aliceToBob(second, "six");
		assertTrue(second.bob.lastFrameCarriedPqSecret());
		MlKemKeyPair secondKey = second.bob.currentMode3FullState()
				.getOurActiveKeyPair();
		byte[] secondEk = secondKey.getEncapsulationKey().clone();
		byte[] secondDk = secondKey.getDecapsulationKey().clone();
		assertFalse(Arrays.equals(copiedDk, secondDk));
		second.close();
		byte[] recorded = wire[0].copy.toByteArray();

		ZwfMode3FullStreamDecrypter withCopy = rootHolderReading(recorded,
				root, new MlKemKeyPair(secondEk, copiedDk, ekSeed, ekVector));
		assertEquals("the opening cover frame depends on the root alone",
				"four", readText(withCopy));
		try {
			readText(withCopy);
			fail("the root and a key from an earlier connection must not "
					+ "open a frame of this one");
		} catch (FormatException expected) {
		}

		ZwfMode3FullStreamDecrypter withKey = rootHolderReading(recorded,
				root, new MlKemKeyPair(secondEk, secondDk, ekSeed, ekVector));
		assertEquals("four", readText(withKey));
		assertEquals("the root and the key pair still held open it", "six",
				readText(withKey));
	}

	@Test(timeout = 60_000)
	public void aConnectionThatReachesTheIntervalRotatesOnceAndTheNextStartsFresh()
			throws Exception {
		SecretKey root = randomRoot();
		ZwfStreamCounter aliceCounter = new ZwfStreamCounter(new MemStore());
		ZwfStreamCounter bobCounter = new ZwfStreamCounter(new MemStore());
		Link l = connect(root, aliceCounter, bobCounter);
		Set<KpId> seen = new HashSet<>();
		KpId start = activeId(l.bob);
		seen.add(start);
		for (int i = 0; i < MODE3_FULL_SEND_ROTATION_INTERVAL; i++) {
			aliceToBob(l, "a" + i);
			bobToAlice(l, "b" + i);
			seen.add(activeId(l.bob));
		}
		assertEquals("sixteen own frames rotate exactly once", 2, seen.size());
		KpId rotated = activeId(l.bob);
		assertFalse(start.equals(rotated));
		assertEquals("the peer has not used the new key yet", start,
				l.bob.currentMode3FullState().getPeerUsedKpId());
		aliceToBob(l, "after rotation");
		assertTrue(l.bob.lastFrameCarriedPqSecret());
		assertEquals("the next peer frame uses the new key", rotated,
				l.bob.currentMode3FullState().getPeerUsedKpId());
		assertNull(l.bob.currentMode3FullState().findKeypairById(start));
		l.close();

		Link next = connect(root, aliceCounter, bobCounter);
		assertFalse(seen.contains(activeId(next.bob)));
		aliceToBob(next, "again");
		bobToAlice(next, "again back");
		next.close();
	}

	@Test(timeout = 60_000)
	public void aFrameDroppedAfterARotationStopsTheStream() throws Exception {
		Link l = faultyLink(Fault.DROP);
		runPastAliceRotation(l);
		l.alice.sendMessage(utf8("dropped"));
		l.alice.sendMessage(utf8("after the gap"));
		assertNextFrameRefused(l);
		l.close();
	}

	@Test(timeout = 60_000)
	public void aFrameRepeatedAfterARotationStopsTheStream() throws Exception {
		Link l = faultyLink(Fault.REPEAT);
		runPastAliceRotation(l);
		aliceToBob(l, "sent twice");
		assertNextFrameRefused(l);
		l.close();
	}

	@Test(timeout = 60_000)
	public void framesSwappedAfterARotationStopTheStream() throws Exception {
		Link l = faultyLink(Fault.SWAP);
		runPastAliceRotation(l);
		l.alice.sendMessage(utf8("first"));
		l.alice.sendMessage(utf8("second"));
		assertNextFrameRefused(l);
		l.close();
	}
}
