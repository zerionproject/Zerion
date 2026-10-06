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
		ratchet = new PcsRatchetImpl(crypto);
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
					mode3FullRatchet, tag, streamId, streamHeaderKey,
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
					mode3FullRatchet, tag, streamId, header,
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

	@Test(timeout = 300_000)
	public void rotationPausesAtTheBoundInsteadOfEvictingAUsableKeyPair()
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
		for (int i = 1; i < MODE3_FULL_RECV_SK_LRU_SIZE; i++) {
			rotateOnce(b, a);
		}
		assertEquals(MODE3_FULL_RECV_SK_LRU_SIZE,
				b.shared.get().getRecentKeyPairs().size());
		assertFalse(b.shared.get().canRotate());
		MlKemKeyPair paused = b.activeKeyPair();
		for (int i = 0; i < 3 * MODE3_FULL_SEND_ROTATION_INTERVAL; i++) {
			b.send("paused " + i);
			assertEquals("paused " + i, a.receive());
		}
		assertSame("no rotation while the peer has not been heard from",
				paused, b.activeKeyPair());
		assertNotNull("the oldest retained key pair is still usable",
				b.shared.get().findKeypairById(idOf(old)));
		assertEquals("still open at the bound",
				stale.roundTrip("still open at the bound"));

		a.send("heard from again");
		assertEquals("heard from again", b.receive());
		assertNull("pruned once the peer used the newest key pair",
				b.shared.get().findKeypairById(idOf(old)));
		assertTrue(b.shared.get().canRotate());
		try {
			stale.roundTrip("after pruning");
			fail("a ciphertext for a pruned key pair must be refused");
		} catch (FormatException expected) {
		}
		rotateOnce(b, a);
	}

	@Test(timeout = 300_000)
	public void frameQueuedAcrossManyOwnRotationsStillOpens()
			throws Exception {
		Peer[] p = connectedPair();
		Peer a = p[0], b = p[1];
		a.send("queued while the reader stalls");
		int batches = MODE3_FULL_RECV_SK_LRU_SIZE + 8;
		for (int i = 0; i < batches * MODE3_FULL_SEND_ROTATION_INTERVAL; i++) {
			b.send("own send " + i);
			assertEquals("own send " + i, a.receive());
		}
		assertEquals("queued while the reader stalls", b.receive());
		a.send("and the next one");
		assertEquals("and the next one", b.receive());
	}

	private static final int MAX_UNREAD_LEAD =
			(MODE3_FULL_RECV_SK_LRU_SIZE / 2)
					* MODE3_FULL_SEND_ROTATION_INTERVAL;

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
