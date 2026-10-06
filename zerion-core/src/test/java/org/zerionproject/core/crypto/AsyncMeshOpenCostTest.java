package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncMeshDelivery;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.system.SystemClock;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AsyncMeshOpenCostTest {

	private static final int FORGERIES = 300;
	private static final int MAX_DECAPSULATIONS = 64;

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent real;
	private final AtomicInteger decapsulations = new AtomicInteger();
	private final AtomicInteger signatureChecks = new AtomicInteger();
	private CryptoComponent counting;

	@Before
	public void setUp() {
		real = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		counting = (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					if (method.getName().equals("deriveHybridSharedSecret")) {
						decapsulations.incrementAndGet();
					}
					if (method.getName().equals("verifyHybridSignature")) {
						signatureChecks.incrementAndGet();
					}
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	@Test
	public void aFloodOfForgedEnvelopesCostsABoundedNumberOfOpens()
			throws Exception {
		AsyncMeshDelivery delivery = recipient(new FrozenClock());
		for (int i = 0; i < FORGERIES; i++) {
			delivery.onFrame(forgery(i % 2 == 0 ? 0L : 1L));
		}
		assertTrue("decapsulations: " + decapsulations.get(),
				decapsulations.get() <= MAX_DECAPSULATIONS);
		assertEquals(0, signatureChecks.get());
	}

	@Test
	public void aRepeatedForgeryIsOpenedOnce() throws Exception {
		AsyncMeshDelivery delivery = recipient(new SystemClock());
		byte[] forged = forgery(0L);
		byte[] forgedLegacy = forgery(1L);
		for (int i = 0; i < 50; i++) {
			delivery.onFrame(forged);
			delivery.onFrame(forgedLegacy);
		}
		assertTrue("decapsulations: " + decapsulations.get(),
				decapsulations.get() <= 2);
		assertEquals(0, signatureChecks.get());
	}

	private AsyncMeshDelivery recipient(Clock clock) throws Exception {
		AsyncPrekeyStore store = new AsyncPrekeyStore(counting,
				new InMemorySettingsManager(), new SystemClock());
		store.getSignedPrekey();
		KeyPair sig = real.generateHybridSignatureKeyPair();
		KeyPair agree = real.generateHybridAgreementKeyPair();
		AsyncMeshDelivery.Identity id = new AsyncMeshDelivery.Identity(
				sig.getPublic().getEncoded(), sig.getPrivate(),
				agree.getPublic().getEncoded());
		List<byte[]> opened = new ArrayList<>();
		return new AsyncMeshDelivery(counting, new AsyncSealedSender(counting),
				store, (s, t, p, ts) -> opened.add(p), id, clock);
	}

	private byte[] forgery(long signedPrekeyId) {
		byte[] ephemeral = real.generateHybridAgreementKeyPair().getPublic()
				.getEncoded();
		byte[] kemCiphertext =
				new byte[PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES];
		random.nextBytes(kemCiphertext);
		byte[] dedup = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		random.nextBytes(dedup);
		byte[] blob = new byte[9500];
		random.nextBytes(blob);
		return new AsyncEnvelope(AsyncEnvelope.PREKEY_KIND_SIGNED,
				new byte[AsyncEnvelope.PREKEY_ID_BYTES], signedPrekeyId,
				ephemeral, kemCiphertext, 3600L, dedup, blob).encode();
	}

	private static final class FrozenClock implements Clock {
		private final long now = System.currentTimeMillis();

		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	}
}
