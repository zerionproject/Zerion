package org.zerionproject.handshake;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.contact.HandshakeCrypto;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Real-crypto test of the native handshake: two parties, each holding their own
 * hybrid static key and the other's commitment, run the handshake over in-memory
 * pipes and must independently derive the same root key with opposite roles.
 */
public class ZwfHandshakeTest {

	private CryptoComponent crypto;
	private HandshakeCrypto handshakeCrypto;

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
	}

	private byte[] commitment(KeyPair kp) {
		return crypto.hash(HYBRID_COMMITMENT_LABEL,
				kp.getPublic().getEncoded());
	}

	@Test(timeout = 30_000)
	public void bothPartiesDeriveTheSameRootKey() throws Exception {
		KeyPair staticA = handshakeCrypto.generateHybridEphemeralKeyPair();
		KeyPair staticB = handshakeCrypto.generateHybridEphemeralKeyPair();
		byte[] commitA = commitment(staticA);
		byte[] commitB = commitment(staticB);

		// alice's out -> bob's in, and vice versa; large buffers so the ~1.2 KB
		// key messages never block a writer.
		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);

		AtomicReference<ZwfHandshakeResult> resA = new AtomicReference<>();
		AtomicReference<ZwfHandshakeResult> resB = new AtomicReference<>();
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

		Thread ta = new Thread(() -> {
			try {
				resA.set(new ZwfHandshake(crypto, handshakeCrypto)
						.run(staticA, commitB, aIn, aOut));
			} catch (Throwable t) {
				errors.add(t);
			}
		}, "party-A");
		Thread tb = new Thread(() -> {
			try {
				resB.set(new ZwfHandshake(crypto, handshakeCrypto)
						.run(staticB, commitA, bIn, bOut));
			} catch (Throwable t) {
				errors.add(t);
			}
		}, "party-B");

		ta.start();
		tb.start();
		ta.join(20_000);
		tb.join(20_000);

		synchronized (errors) {
			if (!errors.isEmpty()) {
				throw new AssertionError("handshake threw: " + errors.get(0),
						errors.get(0));
			}
		}
		ZwfHandshakeResult a = resA.get();
		ZwfHandshakeResult b = resB.get();
		assertNotNull("party A produced no result", a);
		assertNotNull("party B produced no result", b);

		// The whole point: identical shared root key.
		assertArrayEquals("root keys must match",
				a.getRootKey().getBytes(), b.getRootKey().getBytes());
		// Exactly one side is alice.
		assertTrue(a.isAlice() != b.isAlice());
		assertTrue(a.isMode3Capable());
		assertTrue(b.isMode3Capable());
		// Each side sees the other's static key as the peer key.
		assertArrayEquals(a.getOurStaticPublicKey(), b.getTheirStaticPublicKey());
		assertArrayEquals(b.getOurStaticPublicKey(), a.getTheirStaticPublicKey());
		// Ephemeral X25519 halves cross-match too.
		assertArrayEquals(a.getOurEphemeralX25519(),
				b.getTheirEphemeralX25519());
	}

	@Test(timeout = 30_000)
	public void wrongCommitmentIsRejected() throws Exception {
		KeyPair staticA = handshakeCrypto.generateHybridEphemeralKeyPair();
		KeyPair staticB = handshakeCrypto.generateHybridEphemeralKeyPair();
		KeyPair impostor = handshakeCrypto.generateHybridEphemeralKeyPair();
		byte[] commitA = commitment(staticA);
		// Force party A into the alice (send-first) role so it deterministically
		// reaches the commitment check rather than the two sides deadlocking on
		// who receives first. A is alice iff its own commitment sorts below the
		// one it expects, so keep drawing an impostor until that holds.
		byte[] wrongCommitForA = commitment(impostor);
		while (Bytes.compare(commitA, wrongCommitForA) >= 0) {
			wrongCommitForA = commitment(
					handshakeCrypto.generateHybridEphemeralKeyPair());
		}
		final byte[] expectByA = wrongCommitForA;

		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);

		CountDownLatch rejected = new CountDownLatch(1);
		Thread ta = new Thread(() -> {
			try {
				new ZwfHandshake(crypto, handshakeCrypto)
						.run(staticA, expectByA, aIn, aOut);
			} catch (Throwable t) {
				rejected.countDown();
			}
		}, "party-A");
		Thread tb = new Thread(() -> {
			try {
				new ZwfHandshake(crypto, handshakeCrypto)
						.run(staticB, commitA, bIn, bOut);
			} catch (Throwable ignored) {
			}
		}, "party-B");
		ta.setDaemon(true);
		tb.setDaemon(true);
		ta.start();
		tb.start();

		assertTrue("expected the commitment mismatch to be rejected",
				rejected.await(20, TimeUnit.SECONDS));
	}
}
