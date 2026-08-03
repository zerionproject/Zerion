package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.contact.HandshakeCrypto;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Full-stack integration over in-memory pipes: two parties each turn a raw
 * stream pair into a live connection via {@link ZtpConnectionEstablisher}
 * (handshake + session + duplex connection), then exchange post-quantum messages
 * both ways. Proves the entire native transport works as one flow, without Tor.
 */
public class ZtpConnectionEstablisherTest {

	private CryptoComponent crypto;
	private HandshakeCrypto handshakeCrypto;
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
		Class<?> hcImpl = Class.forName(
				"org.zerionproject.core.contact.HandshakeCryptoImpl");
		Constructor<?> hcc = hcImpl.getDeclaredConstructor(CryptoComponent.class);
		hcc.setAccessible(true);
		handshakeCrypto = (HandshakeCrypto) hcc.newInstance(crypto);
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

	private byte[] commitment(KeyPair kp) {
		return crypto.hash(HYBRID_COMMITMENT_LABEL,
				kp.getPublic().getEncoded());
	}

	private ZtpConnectionEstablisher establisher() {
		Supplier<AuthenticatedCipher> cipherFactory =
				XSalsa20Poly1305AuthenticatedCipher::new;
		return new ZtpConnectionEstablisher(crypto, handshakeCrypto, ratchet,
				mode3FullRatchet, sessionFactory,
				new ZwfStreamCounter(new MemStore()), cipherFactory);
	}

	@Test(timeout = 60_000)
	public void establishThenExchangeMessages() throws Exception {
		KeyPair staticA = handshakeCrypto.generateHybridEphemeralKeyPair();
		KeyPair staticB = handshakeCrypto.generateHybridEphemeralKeyPair();
		byte[] commitA = commitment(staticA);
		byte[] commitB = commitment(staticB);

		// A real loopback socket pair - like a Tor socket, and unlike piped
		// streams it does not tie liveness to the writer thread (which dies when
		// establish() returns before the messaging threads run).
		java.net.ServerSocket server = new java.net.ServerSocket(0, 1,
				java.net.InetAddress.getByName("127.0.0.1"));
		java.net.Socket sA = new java.net.Socket();
		sA.connect(new java.net.InetSocketAddress("127.0.0.1",
				server.getLocalPort()));
		java.net.Socket sB = server.accept();
		java.io.InputStream aIn = sA.getInputStream();
		java.io.OutputStream aOut = sA.getOutputStream();
		java.io.InputStream bIn = sB.getInputStream();
		java.io.OutputStream bOut = sB.getOutputStream();

		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		AtomicReference<ZwfDuplexConnection> connA = new AtomicReference<>();
		AtomicReference<ZwfDuplexConnection> connB = new AtomicReference<>();

		// Each side establishes concurrently (the handshake blocks until both run).
		ZtpConnectionEstablisher estA = establisher();
		ZtpConnectionEstablisher estB = establisher();
		Thread ea = new Thread(() -> {
			try {
				connA.set(estA.establish(1, staticA, commitB, aIn, aOut));
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread eb = new Thread(() -> {
			try {
				connB.set(estB.establish(2, staticB, commitA, bIn, bOut));
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		ea.start();
		eb.start();
		ea.join(30_000);
		eb.join(30_000);
		checkNoErrors(errors);
		assertNotNull(connA.get());
		assertNotNull(connB.get());

		int n = 6;
		List<String> gotByB = Collections.synchronizedList(new ArrayList<>());
		List<String> gotByA = Collections.synchronizedList(new ArrayList<>());
		Thread aSend = new Thread(() -> runSafe(errors, () -> {
			for (int i = 0; i < n; i++) {
				connA.get().sendMessage(("a2b-" + i)
						.getBytes(StandardCharsets.UTF_8));
			}
		}));
		Thread bSend = new Thread(() -> runSafe(errors, () -> {
			for (int i = 0; i < n; i++) {
				connB.get().sendMessage(("b2a-" + i)
						.getBytes(StandardCharsets.UTF_8));
			}
		}));
		Thread bRecv = new Thread(() -> runSafe(errors, () -> {
			for (int i = 0; i < n; i++) {
				gotByB.add(new String(connB.get().receiveMessage(),
						StandardCharsets.UTF_8));
			}
		}));
		Thread aRecv = new Thread(() -> runSafe(errors, () -> {
			for (int i = 0; i < n; i++) {
				gotByA.add(new String(connA.get().receiveMessage(),
						StandardCharsets.UTF_8));
			}
		}));
		aSend.start();
		bSend.start();
		bRecv.start();
		aRecv.start();
		aSend.join(20_000);
		bSend.join(20_000);
		bRecv.join(20_000);
		aRecv.join(20_000);
		checkNoErrors(errors);

		assertEquals(n, gotByB.size());
		assertEquals(n, gotByA.size());
		for (int i = 0; i < n; i++) {
			assertEquals("a2b-" + i, gotByB.get(i));
			assertEquals("b2a-" + i, gotByA.get(i));
		}
		// Post-quantum engaged end-to-end.
		assertNotNull(connA.get().currentMode3FullState().getTheirActivePqPk());
		assertNotNull(connB.get().currentMode3FullState().getTheirActivePqPk());
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static void runSafe(List<Throwable> errors, ThrowingRunnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			errors.add(t);
		}
	}

	private static void checkNoErrors(List<Throwable> errors) {
		synchronized (errors) {
			if (!errors.isEmpty()) {
				throw new AssertionError("stack threw: " + errors.get(0),
						errors.get(0));
			}
		}
	}
}
