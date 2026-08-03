package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end proof of the Phase 2 + Phase 3 software pipeline with no radio:
 * the recipient generates prekeys and publishes a signed bundle, the sender
 * seals a message to it and floods it across the mesh, and the recipient opens
 * it and consumes the one-time prekey. Relays cannot open it.
 */
public class AsyncMeshIntegrationTest {

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent crypto;
	private AsyncSealedSender sealer;

	@Before
	public void setUp() {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		sealer = new AsyncSealedSender(crypto);
	}

	private AsyncMeshDelivery.Identity newIdentity() {
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		KeyPair agree = crypto.generateHybridAgreementKeyPair();
		return new AsyncMeshDelivery.Identity(sig.getPublic().getEncoded(),
				sig.getPrivate(), agree.getPublic().getEncoded());
	}

	private void connect(MeshForwarder a, String aId, MeshForwarder b,
			String bId) {
		a.addLink(link(aId, b, bId));
		b.addLink(link(bId, a, aId));
	}

	private MeshLink link(String id, MeshForwarder target, String inbound) {
		return new MeshLink() {
			@Override
			public String getId() {
				return id;
			}

			@Override
			public void broadcast(byte[] frame) {
				target.onReceive(frame, inbound);
			}
		};
	}

	@Test
	public void sealFloodOpenAndConsume() throws Exception {
		// Recipient R: identity, prekey store, published bundle.
		AsyncMeshDelivery.Identity rId = newIdentity();
		AsyncPrekeyStore rStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		AsyncMeshDelivery.Identity rSigningIdentity = rId;
		List<AsyncPrekeyBundle.OneTimePrekey> otks =
				rStore.generateOneTimePrekeys(5);
		AsyncPrekeyStore.SignedPrekey spk = rStore.getSignedPrekey();
		AsyncPrekeyBundle bundle = AsyncPrekeyBundle.create(crypto,
				rSigningIdentity.sigPub, rSigningIdentity.sigPriv,
				rSigningIdentity.agreePub, spk.id, spk.pub, spk.expiry, otks);
		assertTrue(bundle.verify(crypto));

		List<byte[]> rOpened = new ArrayList<>();
		AsyncMeshDelivery rDelivery = new AsyncMeshDelivery(crypto, sealer,
				rStore, (senderPub, type, payload, ts) -> rOpened.add(payload),
				rId);
		MeshForwarder rForwarder = new MeshForwarder(rDelivery, random);

		// Sender S: identity, its own store and delivery (as a mesh node).
		AsyncMeshDelivery.Identity sId = newIdentity();
		AsyncPrekeyStore sStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		AsyncMeshDelivery sDelivery = new AsyncMeshDelivery(crypto, sealer,
				sStore, (senderPub, type, payload, ts) -> true, sId);
		MeshForwarder sForwarder = new MeshForwarder(sDelivery, random);

		// A relay in the middle that cannot open anything.
		AsyncMeshDelivery.Identity relayId = newIdentity();
		AsyncPrekeyStore relayStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		List<byte[]> relayOpened = new ArrayList<>();
		AsyncMeshDelivery relayDelivery = new AsyncMeshDelivery(crypto, sealer,
				relayStore,
				(senderPub, type, payload, ts) -> relayOpened.add(payload),
				relayId);
		MeshForwarder relayForwarder =
				new MeshForwarder(relayDelivery, random);

		connect(sForwarder, "s-r", relayForwarder, "r-s");
		connect(relayForwarder, "r-x", rForwarder, "x-r");

		byte[] payload = "offline mesh hello".getBytes();
		sDelivery.send(sForwarder, bundle, 9, payload, 3600L, 1234567890L, true);

		assertEquals(1, rOpened.size());
		assertArrayEquals(payload, rOpened.get(0));
		assertEquals(0, relayOpened.size());
	}
}
