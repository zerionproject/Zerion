package org.zerionproject.crypto;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.DhRatchetState;
import org.zerionproject.core.api.crypto.pcs.KpId;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
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

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_RECV_SK_LRU_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_SEND_ROTATION_INTERVAL;
import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The send and receive directions of one duplex connection share one Mode
 * 3-Full state. The receive side snapshots that state, releases the lock,
 * opens the body, and then publishes a state built from its snapshot. If the
 * send side rotates its ML-KEM key pair in between, the receive side's
 * publication must not discard the rotated key pair the peer was just told
 * to use. These tests force that interleaving deterministically with a lock
 * whose release hands control to the send side, without any timing.
 */
public class ZwfMode3FullSharedStateRaceTest {

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
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
		mode3FullRatchet = (Mode3FullRatchet) ratchetCtor.newInstance(crypto,
				mlKemProvider);
	}

	/**
	 * A lock that runs one action right after the next release, on the
	 * releasing thread. The action itself may take the lock again (it is
	 * reentrant), which reproduces a send that lands exactly in the gap
	 * between the receiver's snapshot and its commit.
	 */
	static final class HandoverLock implements Lock {
		private final ReentrantLock delegate = new ReentrantLock();
		private final AtomicReference<Runnable> afterNextUnlock =
				new AtomicReference<>();

		void afterNextUnlock(Runnable action) {
			afterNextUnlock.set(action);
		}

		@Override
		public void lock() {
			delegate.lock();
		}

		@Override
		public void lockInterruptibly() throws InterruptedException {
			delegate.lockInterruptibly();
		}

		@Override
		public boolean tryLock() {
			return delegate.tryLock();
		}

		@Override
		public boolean tryLock(long time, TimeUnit unit)
				throws InterruptedException {
			return delegate.tryLock(time, unit);
		}

		@Override
		public void unlock() {
			delegate.unlock();
			Runnable action = afterNextUnlock.getAndSet(null);
			if (action != null) action.run();
		}

		@Override
		public Condition newCondition() {
			return delegate.newCondition();
		}
	}

	/** One peer: a shared state, its lock, and the two stream directions. */
	final class Peer {
		final AtomicReference<Mode3FullState> shared;
		final HandoverLock lock = new HandoverLock();
		final PcsSessionState sendState;
		final PcsSessionState recvState;
		final PipedOutputStream out = new PipedOutputStream();
		final PipedInputStream in;
		ZwfMode3FullStreamEncrypter enc;
		ZwfMode3FullStreamDecrypter dec;
		final boolean alice;

		Peer(SecretKey rootKey, boolean alice) throws IOException {
			this.alice = alice;
			Mode3FullState initial = mode3FullRatchet.createInitialState();
			shared = new AtomicReference<>(initial);
			sendState = stateWith(rootKey, initial);
			recvState = stateWith(rootKey, initial);
			in = new PipedInputStream(1 << 22);
		}

		void connect(Peer other, byte[] tag, long streamId,
				SecretKey streamHeaderKey) throws IOException {
			out.connect(other.in);
			enc = new ZwfMode3FullStreamEncrypter(out, cipher(), ratchet,
					mode3FullRatchet, streamId, tag, randomBytes(24),
					streamHeaderKey, sendState, null, shared::get, shared::set,
					lock, alice);
		}

		void listen(byte[] tag, long streamId, SecretKey streamHeaderKey) {
			dec = new ZwfMode3FullStreamDecrypter(in, cipher(), ratchet,
					mode3FullRatchet, null, tag, streamId, streamHeaderKey,
					recvState, null, shared::get, shared::set, lock, !alice);
		}

		void send(String text) throws IOException {
			byte[] b = text.getBytes(StandardCharsets.UTF_8);
			enc.writeFrame(b, b.length, false);
		}

		String receive() throws IOException {
			byte[] buf = new byte[FRAME_LENGTH];
			int n = dec.readFrame(buf);
			return new String(buf, 0, n, StandardCharsets.UTF_8);
		}

		MlKemKeyPair activeKeyPair() {
			return shared.get().getOurActiveKeyPair();
		}
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

	/** Two peers wired both ways, with both directions bootstrapped. */
	private Peer[] connectedPair() throws IOException {
		SecretKey rootKey = randomKey();
		Peer a = new Peer(rootKey, true);
		Peer b = new Peer(rootKey, false);
		byte[] tagAb = randomBytes(TAG_LENGTH);
		byte[] tagBa = randomBytes(TAG_LENGTH);
		SecretKey headerAb = randomKey();
		SecretKey headerBa = randomKey();
		a.connect(b, tagAb, 1L, headerAb);
		b.listen(tagAb, 1L, headerAb);
		b.connect(a, tagBa, 2L, headerBa);
		a.listen(tagBa, 2L, headerBa);
		a.send("hello");
		assertEquals("hello", b.receive());
		b.send("hello back");
		assertEquals("hello back", a.receive());
		a.send("both keys known");
		assertEquals("both keys known", b.receive());
		assertNotNull(a.shared.get().getTheirActivePqPk());
		assertNotNull(b.shared.get().getTheirActivePqPk());
		return new Peer[] {a, b};
	}

	/** Sends from {@code from} to {@code to} until the sender has rotated. */
	private int rotateOnce(Peer from, Peer to) throws IOException {
		MlKemKeyPair before = from.activeKeyPair();
		int sent = 0;
		while (from.activeKeyPair() == before) {
			from.send("filler " + sent);
			assertEquals("filler " + sent, to.receive());
			sent++;
		}
		return sent;
	}

	/**
	 * A second stream from Alice to Bob whose sender keeps a frozen view of
	 * Bob's key (no refresh), read by a second Bob decrypter that shares Bob's
	 * state and lock like the first one.
	 */
	private final class FrozenStream {
		final ZwfMode3FullStreamEncrypter enc;
		final ZwfMode3FullStreamDecrypter dec;

		FrozenStream(Peer from, Peer to, Mode3FullState frozenSenderView,
				long streamId) throws IOException {
			byte[] tag = randomBytes(TAG_LENGTH);
			SecretKey header = randomKey();
			PipedOutputStream out = new PipedOutputStream();
			PipedInputStream in = new PipedInputStream(out, 1 << 22);
			enc = new ZwfMode3FullStreamEncrypter(out, cipher(), ratchet,
					mode3FullRatchet, streamId, tag, randomBytes(24), header,
					stateWith(from.sendState.getRootKey(), frozenSenderView),
					null, () -> frozenSenderView, null, null, from.alice);
			dec = new ZwfMode3FullStreamDecrypter(in, cipher(), ratchet,
					mode3FullRatchet, null, tag, streamId, header,
					stateWith(to.recvState.getRootKey(), to.shared.get()),
					null, to.shared::get, to.shared::set, to.lock, from.alice);
		}

		String roundTrip(String text) throws IOException {
			byte[] b = text.getBytes(StandardCharsets.UTF_8);
			enc.writeFrame(b, b.length, false);
			byte[] buf = new byte[FRAME_LENGTH];
			int n = dec.readFrame(buf);
			return new String(buf, 0, n, StandardCharsets.UTF_8);
		}
	}

	private static KpId idOf(MlKemKeyPair kp) {
		return KpId.of(kp.getEncapsulationKey());
	}

	@Test
	public void sendRotatesAtTheSixteenthSend() throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		MlKemKeyPair first = b.activeKeyPair();
		int sends = 0;
		while (b.activeKeyPair() == first) {
			b.send("count " + sends);
			assertEquals("count " + sends, a.receive());
			sends++;
		}
		assertEquals("the bootstrap send plus fifteen more precede the first "
				+ "rotation", MODE3_FULL_SEND_ROTATION_INTERVAL, sends + 1);
		MlKemKeyPair second = b.activeKeyPair();
		int more = 0;
		while (b.activeKeyPair() == second) {
			b.send("again " + more);
			assertEquals("again " + more, a.receive());
			more++;
		}
		assertEquals(MODE3_FULL_SEND_ROTATION_INTERVAL, more);
	}

	/**
	 * The race: Bob's receiver snapshots the shared state, Bob's sender
	 * rotates and advertises the new key pair in the gap, Bob's receiver
	 * commits. Alice learns the new key pair from the rotation frame and
	 * encapsulates to it. Bob must still be able to open that frame, and his
	 * shared state must hold the rotated key pair as active.
	 */
	@Test
	public void sendRotationBetweenReceiverSnapshotAndCommitIsNotLost()
			throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		MlKemKeyPair k1 = b.activeKeyPair();
		int filler = 0;
		while (true) {
			MlKemKeyPair probe = b.activeKeyPair();
			b.send("probe " + filler);
			assertEquals("probe " + filler, a.receive());
			filler++;
			if (b.activeKeyPair() != probe) break;
		}
		MlKemKeyPair k2 = b.activeKeyPair();
		for (int i = 0; i < MODE3_FULL_SEND_ROTATION_INTERVAL - 1; i++) {
			b.send("towards rotation " + i);
			assertEquals("towards rotation " + i, a.receive());
		}
		assertSame("fifteen sends since the rotation, the next one rotates",
				k2, b.activeKeyPair());

		a.send("arrives while bob is about to rotate");
		List<Throwable> sendFailure = new ArrayList<>();
		b.lock.afterNextUnlock(() -> {
			try {
				b.send("rotation lands in the receiver's gap");
			} catch (IOException e) {
				sendFailure.add(e);
			}
		});
		assertEquals("arrives while bob is about to rotate", b.receive());
		assertTrue(sendFailure.isEmpty());
		MlKemKeyPair k3 = b.shared.get().getOurActiveKeyPair();
		assertTrue("the send in the gap rotated", k3 != k2 && k3 != k1);
		assertEquals("the rotated key pair must be the active one after the "
				+ "receiver's commit", idOf(k3),
				idOf(b.shared.get().getOurActiveKeyPair()));
		assertNotNull("the retired key pair stays in the recent window",
				b.shared.get().getRecentKeyPairs().get(idOf(k2)));

		assertEquals("rotation lands in the receiver's gap", a.receive());
		assertArrayEquals("alice learned the rotated key",
				k3.getEncapsulationKey(),
				a.shared.get().getTheirActivePqPk());
		a.send("encapsulated to the rotated key");
		assertEquals("encapsulated to the rotated key", b.receive());
		b.send("and bob still sends");
		assertEquals("and bob still sends", a.receive());
	}

	/** The same interleaving on every rotation, many times over. */
	@Test(timeout = 300_000)
	public void repeatedRotationsInTheReceiverGapNeverLoseAKey()
			throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		int rotationsForced = 0;
		int frames = 0;
		while (rotationsForced < 40) {
			MlKemKeyPair before = b.activeKeyPair();
			a.send("a " + frames);
			List<Throwable> sendFailure = new ArrayList<>();
			int fi = frames;
			b.lock.afterNextUnlock(() -> {
				try {
					b.send("b " + fi);
				} catch (IOException e) {
					sendFailure.add(e);
				}
			});
			assertEquals("a " + frames, b.receive());
			assertTrue(sendFailure.isEmpty());
			assertEquals("b " + frames, a.receive());
			if (b.activeKeyPair() != before) {
				rotationsForced++;
				assertArrayEquals(b.activeKeyPair().getEncapsulationKey(),
						a.shared.get().getTheirActivePqPk());
			}
			frames++;
		}
		assertTrue(frames >= 39 * MODE3_FULL_SEND_ROTATION_INTERVAL);
	}

	/** A stale commit must not bring a retired key pair back as active. */
	@Test
	public void staleReceiverCommitCannotRestoreARetiredKeyPair()
			throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		rotateOnce(b, a);
		MlKemKeyPair k = b.activeKeyPair();
		for (int i = 0; i < MODE3_FULL_SEND_ROTATION_INTERVAL - 1; i++) {
			b.send("s " + i);
			assertEquals("s " + i, a.receive());
		}
		a.send("x");
		b.lock.afterNextUnlock(() -> {
			try {
				b.send("rotating");
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		assertEquals("x", b.receive());
		Mode3FullState after = b.shared.get();
		assertFalse("the retired key pair is not active any more",
				idOf(k).equals(idOf(after.getOurActiveKeyPair())));
		assertNotNull(after.getRecentKeyPairs().get(idOf(k)));
		assertTrue("counters stay monotonic",
				after.getMessageCounter() > 0);
	}

	/**
	 * A peer that has not yet seen a rotation may keep encapsulating to the
	 * previous key pair: it is accepted while that pair sits in the recent
	 * window and refused once the window has evicted it.
	 */
	@Test(timeout = 300_000)
	public void retiredKeyPairsAreAcceptedOnlyInsideTheRecentWindow()
			throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		MlKemKeyPair old = b.activeKeyPair();
		Mode3FullState aliceFrozenView = a.shared.get();
		FrozenStream stale = new FrozenStream(a, b, aliceFrozenView, 7L);
		assertEquals("before any rotation", stale.roundTrip("before any rotation"));
		rotateOnce(b, a);
		assertNotNull(b.shared.get().findKeypairById(idOf(old)));
		assertEquals("one rotation back is inside the window",
				stale.roundTrip("one rotation back is inside the window"));
		for (int i = 0; i < MODE3_FULL_RECV_SK_LRU_SIZE + 1; i++) {
			rotateOnce(b, a);
		}
		assertNull("evicted after the window filled",
				b.shared.get().findKeypairById(idOf(old)));
		try {
			stale.roundTrip("after eviction");
			fail("a ciphertext for an evicted key pair must be refused");
		} catch (FormatException expected) {
		}
	}

	/**
	 * A peer keeps 32 retired key pairs (32 rotations of 16 sends). A sender
	 * that has read the peer's frames less recently than that, or a receiver
	 * whose own sends have rotated more often than that since it last read,
	 * legitimately hits an evicted key pair. The cadence-paced production
	 * runner never approaches either bound; the stress test keeps both leads
	 * inside it so that only the shared state race is under test.
	 */
	private static final int MAX_UNREAD_LEAD =
			(MODE3_FULL_RECV_SK_LRU_SIZE / 2)
					* MODE3_FULL_SEND_ROTATION_INTERVAL;

	/**
	 * Both directions run at full speed on two threads for thousands of
	 * frames; no frame may fail to open and every rotation must survive.
	 */
	@Test(timeout = 600_000)
	public void simultaneousSendAndReceiveNeverDropAFrame() throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		int frames = 3000;
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger bReceived = new AtomicInteger();
		AtomicInteger aReceived = new AtomicInteger();
		java.util.concurrent.Semaphore aLead =
				new java.util.concurrent.Semaphore(MAX_UNREAD_LEAD);
		java.util.concurrent.Semaphore bLead =
				new java.util.concurrent.Semaphore(MAX_UNREAD_LEAD);
		java.util.concurrent.Semaphore aOwnLead =
				new java.util.concurrent.Semaphore(MAX_UNREAD_LEAD);
		java.util.concurrent.Semaphore bOwnLead =
				new java.util.concurrent.Semaphore(MAX_UNREAD_LEAD);
		Thread aToB = new Thread(() -> {
			try {
				for (int i = 0; i < frames; i++) {
					aLead.acquire();
					aOwnLead.acquire();
					a.send("a" + i);
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread bToA = new Thread(() -> {
			try {
				for (int i = 0; i < frames; i++) {
					bLead.acquire();
					bOwnLead.acquire();
					b.send("b" + i);
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread bReads = new Thread(() -> {
			try {
				for (int i = 0; i < frames; i++) {
					assertEquals("a" + i, b.receive());
					aLead.release();
					bOwnLead.release();
					bReceived.incrementAndGet();
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread aReads = new Thread(() -> {
			try {
				for (int i = 0; i < frames; i++) {
					assertEquals("b" + i, a.receive());
					bLead.release();
					aOwnLead.release();
					aReceived.incrementAndGet();
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		bReads.start();
		aReads.start();
		aToB.start();
		bToA.start();
		for (Thread t : new Thread[] {aToB, bToA, bReads, aReads}) {
			t.join(300_000);
		}
		if (!errors.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (Throwable t : errors) {
				sb.append(t).append(" received a=").append(aReceived.get())
						.append(" b=").append(bReceived.get()).append(" | ");
				int i = 0;
				for (StackTraceElement el : t.getStackTrace()) {
					sb.append(el).append(" | ");
					if (++i == 6) break;
				}
			}
			fail(sb.toString());
		}
		assertEquals(frames, bReceived.get());
		assertEquals(frames, aReceived.get());
	}

	/** A new connection after a rotation bootstraps from fresh state. */
	@Test
	public void reconnectAfterARotationBootstrapsCleanly() throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		rotateOnce(b, a);
		Peer[] again = connectedPair();
		assertNull("a fresh connection starts without the peer key",
				mode3FullRatchet.createInitialState().getTheirActivePqPk());
		again[0].send("after reconnect");
		assertEquals("after reconnect", again[1].receive());
		again[1].send("both ways");
		assertEquals("both ways", again[0].receive());
	}

	/** The receiver refuses a ciphertext for a key pair it never had. */
	@Test
	public void unknownKeyPairIdIsRefused() throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		Mode3FullState foreign = a.shared.get().withRecvAdvance(
				mode3FullRatchet.createInitialState().getOurActiveKeyPair()
						.getEncapsulationKey());
		FrozenStream stream = new FrozenStream(a, b, foreign, 9L);
		try {
			stream.roundTrip("to a key bob never had");
			fail("a ciphertext for an unknown key pair must be refused");
		} catch (FormatException expected) {
		}
	}
}
