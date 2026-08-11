package org.zerionproject.core.crypto;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.api.crypto.StreamEncrypter;
import org.zerionproject.core.api.crypto.pcs.DhRatchetState;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.pcs.InMemorySkippedKeyStore;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.zerionproject.core.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PcsMode3FullIntegrationTest {

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;
	private SkippedKeyStore skippedKeyStore;

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
		skippedKeyStore = new InMemorySkippedKeyStore();
	}

	private AuthenticatedCipher createCipher() {
		return new XSalsa20Poly1305AuthenticatedCipher();
	}

	private SecretKey randomKey() {
		byte[] keyBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(keyBytes);
		return new SecretKey(keyBytes);
	}

	private PcsSessionState newSendState(SecretKey rootKey,
			Mode3FullState m3fState) {
		KeyPair dhKp = crypto.generateAgreementKeyPair();
		DhRatchetState dh = new DhRatchetState(dhKp, null);
		return PcsSessionState.createInitialMode3Full(rootKey, rootKey, dh,
				m3fState);
	}

	@Test
	public void singleFrameMode3FullRoundTrip() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		Mode3FullState sharedM3f = mode3FullRatchet.createInitialState();

		PcsSessionState senderState = newSendState(rootKey, sharedM3f);
		PcsSessionState receiverState = newSendState(rootKey, sharedM3f);

		byte[] streamHeaderNonce = new byte[24];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);

		AtomicReference<PcsSessionState> sendRef =
				new AtomicReference<>(senderState);
		AtomicReference<Mode3FullState> m3fRef = new AtomicReference<>(sharedM3f);
		Consumer<PcsSessionState> sendCallback = s -> {
			sendRef.set(s);
			if (s.getMode3FullState() != null) {
				m3fRef.set(s.getMode3FullState());
			}
		};
		Supplier<PcsSessionState> sendRefresher = sendRef::get;
		Supplier<Mode3FullState> m3fRefresher = m3fRef::get;
		Lock lock = new ReentrantLock();

		ByteArrayOutputStream encOut = new ByteArrayOutputStream();

		StreamEncrypter encrypter = new PcsStreamEncrypterImpl(
				encOut, createCipher(), ratchet, 1L, null,
				streamHeaderNonce, streamHeaderKey, senderState,
				sendCallback, null, null, null, null, mode3FullRatchet,
				m3fRefresher, sendRefresher, lock);

		byte[] msg = "hello mode3full".getBytes();
		encrypter.writeFrame(msg, msg.length, 0, true);
		encrypter.flush();

		ByteArrayInputStream encIn = new ByteArrayInputStream(
				encOut.toByteArray());

		StreamDecrypter decrypter = new PcsStreamDecrypterImpl(
				encIn, createCipher(), ratchet, skippedKeyStore, new byte[5],
				1L, streamHeaderKey, receiverState, null, null, null, null,
				null, null, mode3FullRatchet, m3fRefresher, null, null);

		byte[] buf = new byte[MAX_PAYLOAD_LENGTH];
		int n = decrypter.readFrame(buf);
		assertTrue("expected positive plaintext length, got " + n, n > 0);
		assertArrayEquals(msg, Arrays.copyOf(buf, n));
	}

	@Test
	public void manyFramesMode3FullRoundTrip() throws Exception {
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey = randomKey();
		Mode3FullState sharedM3f = mode3FullRatchet.createInitialState();

		PcsSessionState senderState = newSendState(rootKey, sharedM3f);
		PcsSessionState receiverState = newSendState(rootKey, sharedM3f);

		byte[] streamHeaderNonce = new byte[24];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);

		AtomicReference<PcsSessionState> sendRef =
				new AtomicReference<>(senderState);
		AtomicReference<Mode3FullState> m3fRef = new AtomicReference<>(sharedM3f);
		Consumer<PcsSessionState> sendCallback = s -> {
			sendRef.set(s);
			if (s.getMode3FullState() != null) {
				m3fRef.set(s.getMode3FullState());
			}
		};
		Lock lock = new ReentrantLock();

		ByteArrayOutputStream encOut = new ByteArrayOutputStream();

		StreamEncrypter encrypter = new PcsStreamEncrypterImpl(
				encOut, createCipher(), ratchet, 1L, null,
				streamHeaderNonce, streamHeaderKey, senderState,
				sendCallback, null, null, null, null, mode3FullRatchet,
				(Supplier<Mode3FullState>) m3fRef::get,
				(Supplier<PcsSessionState>) sendRef::get, lock);

		int N = 10;
		String[] messages = new String[N];
		for (int i = 0; i < N; i++) {
			messages[i] = "msg-" + i + "-payload";
			byte[] m = messages[i].getBytes();
			boolean last = (i == N - 1);
			encrypter.writeFrame(m, m.length, 0, last);
		}
		encrypter.flush();

		ByteArrayInputStream encIn = new ByteArrayInputStream(
				encOut.toByteArray());

		StreamDecrypter decrypter = new PcsStreamDecrypterImpl(
				encIn, createCipher(), ratchet, skippedKeyStore, new byte[5],
				1L, streamHeaderKey, receiverState, null, null, null, null,
				null, null, mode3FullRatchet,
				(Supplier<Mode3FullState>) m3fRef::get, null, null);

		byte[] buf = new byte[MAX_PAYLOAD_LENGTH];
		for (int i = 0; i < N; i++) {
			int n = decrypter.readFrame(buf);
			assertTrue("frame " + i + " decrypt failed, length " + n, n > 0);
			String got = new String(Arrays.copyOf(buf, n));
			assertEquals("frame " + i, messages[i], got);
		}
		assertEquals(-1, decrypter.readFrame(buf));
	}

	@Test(timeout = 30_000)
	public void parallelEncryptersOnSameContactDoNotRace() throws Exception {
		final int N = 20;
		SecretKey rootKey = randomKey();
		SecretKey streamHeaderKey1 = randomKey();
		Mode3FullState initial = mode3FullRatchet.createInitialState();
		Mode3FullState peer = mode3FullRatchet.createInitialState();
		Mode3FullState sharedM3f = initial.withRecvAdvance(
				peer.getOurActiveKeyPair().getEncapsulationKey());

		AtomicReference<PcsSessionState> sendRef = new AtomicReference<>(
				newSendState(rootKey, sharedM3f));
		AtomicReference<Mode3FullState> m3fRef = new AtomicReference<>(sharedM3f);
		Consumer<PcsSessionState> sendCallback = s -> {
			sendRef.set(s);
			if (s.getMode3FullState() != null) {
				m3fRef.set(s.getMode3FullState());
			}
		};
		Lock lock = new ReentrantLock();

		ByteArrayOutputStream s1Out = new ByteArrayOutputStream();
		ByteArrayOutputStream s2Out = new ByteArrayOutputStream();
		byte[] nonce1 = new byte[24];
		byte[] nonce2 = new byte[24];
		crypto.getSecureRandom().nextBytes(nonce1);
		crypto.getSecureRandom().nextBytes(nonce2);

		StreamEncrypter e1 = new PcsStreamEncrypterImpl(
				s1Out, createCipher(), ratchet, 1L, null, nonce1,
				streamHeaderKey1, sendRef.get(),
				sendCallback, null, null, null, null, mode3FullRatchet,
				(Supplier<Mode3FullState>) m3fRef::get,
				(Supplier<PcsSessionState>) sendRef::get, lock);
		StreamEncrypter e2 = new PcsStreamEncrypterImpl(
				s2Out, createCipher(), ratchet, 2L, null, nonce2,
				streamHeaderKey1, sendRef.get(),
				sendCallback, null, null, null, null, mode3FullRatchet,
				(Supplier<Mode3FullState>) m3fRef::get,
				(Supplier<PcsSessionState>) sendRef::get, lock);

		final CountDownLatch start = new CountDownLatch(1);
		final List<Throwable> errors = new ArrayList<>();
		final AtomicInteger sentCount = new AtomicInteger(0);

		Runnable senderJob = () -> {
			try {
				start.await();
				for (int i = 0; i < N; i++) {
					byte[] m = ("p" + i).getBytes();
					boolean last = (i == N - 1);
					if (Thread.currentThread().getName().equals("e1")) {
						e1.writeFrame(m, m.length, 0, last);
					} else {
						e2.writeFrame(m, m.length, 0, last);
					}
					sentCount.incrementAndGet();
				}
				if (Thread.currentThread().getName().equals("e1")) {
					e1.flush();
				} else {
					e2.flush();
				}
			} catch (Throwable t) {
				synchronized (errors) {
					errors.add(t);
				}
			}
		};

		Thread t1 = new Thread(senderJob, "e1");
		Thread t2 = new Thread(senderJob, "e2");
		t1.start();
		t2.start();
		start.countDown();
		t1.join();
		t2.join();

		synchronized (errors) {
			if (!errors.isEmpty()) {
				throw new AssertionError("parallel encrypters threw: "
						+ errors.get(0), errors.get(0));
			}
		}
		assertEquals(2 * N, sentCount.get());

		Mode3FullState finalM3f = m3fRef.get();
		assertTrue(finalM3f.getMessageCounter() >= 2 * N);
		assertTrue(finalM3f.getRecentKeyPairs().size()
				<= org.zerionproject.core.api.crypto.pcs.PcsConstants
						.MODE3_FULL_RECV_SK_LRU_SIZE);
	}

	@Test
	public void pcsStateManagerLockIsSharedAcrossInjectionSites()
			throws Exception {
		Class<?> pcsStateManagerClass = Class.forName(
				"org.zerionproject.core.crypto.pcs.PcsStateManager");
		assertTrue(pcsStateManagerClass.isAnnotationPresent(
				javax.inject.Singleton.class));

		Class<?> lifecycleManagerClass = Class.forName(
				"org.zerionproject.core.api.lifecycle.LifecycleManager");
		Object lifecycleManager = java.lang.reflect.Proxy.newProxyInstance(
				lifecycleManagerClass.getClassLoader(),
				new Class[] {lifecycleManagerClass},
				(proxy, method, methodArgs) -> {
					Class<?> rt = method.getReturnType();
					if (rt == boolean.class) return false;
					if (rt == int.class) return 0;
					if (rt == long.class) return 0L;
					return null;
				});

		java.lang.reflect.Constructor<?> ctor =
				pcsStateManagerClass.getDeclaredConstructor(
						org.zerionproject.core.api.db.DatabaseComponent.class,
						CryptoComponent.class,
						lifecycleManagerClass);
		ctor.setAccessible(true);
		Object stateManager = ctor.newInstance(null, crypto, lifecycleManager);
		java.lang.reflect.Method getContactLock =
				pcsStateManagerClass.getMethod("getContactLock",
						ContactId.class);
		ContactId c = new ContactId(42);
		Object lock1 = getContactLock.invoke(stateManager, c);
		Object lock2 = getContactLock.invoke(stateManager, c);
		assertSame(lock1, lock2);
	}
}
