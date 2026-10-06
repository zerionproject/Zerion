package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncMeshDelivery;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshLink;
import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AsyncMeshOpenBudgetTest {

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent crypto;
	private MutableClock clock;
	private AsyncPrekeyStore store;
	private AsyncPrekeyBundle bundle;
	private AsyncMeshDelivery delivery;
	private final List<byte[]> opened = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		clock = new MutableClock(System.currentTimeMillis());
		store = new AsyncPrekeyStore(crypto, new InMemorySettingsManager(),
				clock);
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		AsyncMeshDelivery.Identity id = new AsyncMeshDelivery.Identity(
				sig.getPublic().getEncoded(), sig.getPrivate(),
				store.getMeshAgreementPublicKey());
		AsyncPrekeyStore.SignedPrekey spk = store.getSignedPrekey();
		bundle = AsyncPrekeyBundle.create(crypto, id.sigPub, id.sigPriv,
				id.agreePub, AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID,
				spk.pub, spk.expiry,
				store.topUpOneTimePrekeys(2, new byte[] {0, 0, 0, 1}));
		delivery = new AsyncMeshDelivery(crypto, new AsyncSealedSender(crypto),
				store, (s, t, p, ts) -> opened.add(p), id, clock);
	}

	@Test
	public void aFloodThroughOneNeighbourDoesNotBlockAnother()
			throws Exception {
		for (int i = 0; i < 200; i++) {
			delivery.onFrame(forgery(), "c:attacker");
		}
		delivery.onFrame(genuine(), "c:attacker");
		assertEquals("the flooding neighbour's share is spent", 0,
				opened.size());
		delivery.onFrame(genuine(), "s:honest");
		assertEquals(1, opened.size());
		clock.now += 2_000L;
		delivery.onFrame(genuine(), "c:attacker");
		assertEquals("its share refills", 2, opened.size());
	}

	private byte[] genuine() throws Exception {
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		AsyncMeshDelivery.Identity sender = new AsyncMeshDelivery.Identity(
				sig.getPublic().getEncoded(), sig.getPrivate(),
				crypto.generateHybridAgreementKeyPair().getPublic()
						.getEncoded());
		AsyncMeshDelivery d = new AsyncMeshDelivery(crypto,
				new AsyncSealedSender(crypto),
				new AsyncPrekeyStore(crypto, new InMemorySettingsManager(),
						clock),
				(a, b, c, e) -> true, sender, clock);
		List<byte[]> captured = new ArrayList<>();
		MeshForwarder relay = new MeshForwarder(captured::add, random);
		MeshForwarder origin = new MeshForwarder(d, random);
		origin.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "l";
			}

			@Override
			public void broadcast(byte[] frame) {
				relay.onReceive(frame, "in");
			}
		});
		d.send(origin, bundle, 9, "hello".getBytes(), 3600L, clock.now,
				false);
		return captured.get(0);
	}

	private byte[] forgery() {
		byte[] ephemeral = crypto.generateHybridAgreementKeyPair().getPublic()
				.getEncoded();
		byte[] kemCiphertext =
				new byte[PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES];
		random.nextBytes(kemCiphertext);
		byte[] dedup = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		random.nextBytes(dedup);
		byte[] blob = new byte[9500];
		random.nextBytes(blob);
		return new AsyncEnvelope(AsyncEnvelope.PREKEY_KIND_SIGNED,
				new byte[AsyncEnvelope.PREKEY_ID_BYTES],
				AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID, ephemeral,
				kemCiphertext, 3600L, dedup, blob).encode();
	}

	private static final class MutableClock implements Clock {
		volatile long now;

		MutableClock(long now) {
			this.now = now;
		}

		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	}
}
