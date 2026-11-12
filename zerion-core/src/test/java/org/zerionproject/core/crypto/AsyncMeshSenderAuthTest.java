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
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AsyncMeshSenderAuthTest {

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent crypto;
	private AsyncSealedSender sealer;
	private AsyncMeshDelivery.Identity sender;
	private byte[] linkKey;
	private AsyncPrekeyStore senderStore;

	@Before
	public void setUp() {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		sealer = new AsyncSealedSender(crypto);
		sender = newIdentity();
		linkKey = bytes(AsyncSealedSender.LINK_KEY_BYTES);
		senderStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
	}

	@Test
	public void keyedEnvelopeOpensAtAContactHoldingTheLinkKey()
			throws Exception {
		Recipient r = new Recipient(keysFor(sender.sigPub, linkKey));
		byte[] payload = "keyed over the mesh".getBytes();

		byte[] envelope = sealTo(r, payload, keysFor(r.sigPub, linkKey));
		r.delivery.onFrame(envelope);

		assertEquals(1, r.opened.size());
		assertArrayEquals(payload, r.opened.get(0));
	}

	@Test
	public void keyedEnvelopeIsRefusedWithoutTheLinkKey() throws Exception {
		Recipient none = new Recipient(null);
		Recipient other = new Recipient(keysFor(sender.sigPub,
				bytes(AsyncSealedSender.LINK_KEY_BYTES)));
		Recipient stranger = new Recipient(peer -> null);

		none.delivery.onFrame(sealTo(none, "a".getBytes(),
				keysFor(none.sigPub, linkKey)));
		other.delivery.onFrame(sealTo(other, "b".getBytes(),
				keysFor(other.sigPub, linkKey)));
		stranger.delivery.onFrame(sealTo(stranger, "c".getBytes(),
				keysFor(stranger.sigPub, linkKey)));

		assertTrue(none.opened.isEmpty());
		assertTrue(other.opened.isEmpty());
		assertTrue(stranger.opened.isEmpty());
	}

	@Test
	public void signedEnvelopeOpensAtAContactHoldingLinkKeys()
			throws Exception {
		Recipient r = new Recipient(keysFor(sender.sigPub, linkKey));
		byte[] payload = "signed as before".getBytes();

		r.delivery.onFrame(sealTo(r, payload, null));
		assertEquals(1, r.opened.size());
		r.delivery.onFrame(sealTo(r, payload, peer -> null));
		assertEquals(2, r.opened.size());
		assertArrayEquals(payload, r.opened.get(0));
		assertArrayEquals(payload, r.opened.get(1));
	}

	@Test
	public void keyedSignedAndCoverEnvelopesHaveTheSameLength()
			throws Exception {
		Recipient r = new Recipient(keysFor(sender.sigPub, linkKey));
		byte[] payload = bytes(777);

		byte[] signed = sealTo(r, payload, null);
		byte[] keyed = sealTo(r, payload, keysFor(r.sigPub, linkKey));
		byte[] cover = cover(payload);

		assertEquals(signed.length, keyed.length);
		assertEquals(signed.length, cover.length);
	}

	private AsyncMeshDelivery.SenderLinkKeys keysFor(byte[] peer,
			byte[] key) {
		return new AsyncMeshDelivery.SenderLinkKeys() {
			@Override
			@Nullable
			public byte[] linkKeyFor(byte[] peerIdentitySigPub) {
				return Arrays.equals(peer, peerIdentitySigPub) ? key : null;
			}

			@Override
			@Nullable
			public byte[] sendingLinkKeyFor(byte[] peerIdentitySigPub) {
				return linkKeyFor(peerIdentitySigPub);
			}
		};
	}

	private byte[] sealTo(Recipient r, byte[] payload,
			@Nullable AsyncMeshDelivery.SenderLinkKeys keys)
			throws Exception {
		AsyncPrekeyStore sStore = senderStore;
		AsyncMeshDelivery sDelivery = new AsyncMeshDelivery(crypto, sealer,
				sStore, (a, b, c, d) -> true, sender, new SystemClock(),
				keys);
		List<byte[]> captured = new ArrayList<>();
		MeshForwarder sForwarder = capturing(sDelivery, captured);
		sDelivery.send(sForwarder, r.bundle, 9, payload, 3600L,
				System.currentTimeMillis(), true);
		assertEquals(1, captured.size());
		return captured.get(0);
	}

	private byte[] cover(byte[] payload) throws Exception {
		AsyncPrekeyStore sStore = senderStore;
		AsyncMeshDelivery sDelivery = new AsyncMeshDelivery(crypto, sealer,
				sStore, (a, b, c, d) -> true, sender, new SystemClock());
		List<byte[]> captured = new ArrayList<>();
		MeshForwarder sForwarder = capturing(sDelivery, captured);
		sDelivery.sendCover(sForwarder, payload, 3600L,
				System.currentTimeMillis(), true);
		assertEquals(1, captured.size());
		return captured.get(0);
	}

	private MeshForwarder capturing(AsyncMeshDelivery delivery,
			List<byte[]> captured) {
		MeshForwarder sForwarder = new MeshForwarder(delivery, random);
		MeshForwarder relay = new MeshForwarder(captured::add, random);
		sForwarder.addLink(link("s-r", relay, "r-s"));
		relay.addLink(link("r-s", sForwarder, "s-r"));
		return sForwarder;
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

	private AsyncMeshDelivery.Identity newIdentity() {
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		KeyPair agree = crypto.generateHybridAgreementKeyPair();
		return new AsyncMeshDelivery.Identity(sig.getPublic().getEncoded(),
				sig.getPrivate(), agree.getPublic().getEncoded());
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	private final class Recipient {
		final byte[] sigPub;
		final AsyncPrekeyBundle bundle;
		final AsyncMeshDelivery delivery;
		final List<byte[]> opened = new ArrayList<>();

		Recipient(@Nullable AsyncMeshDelivery.SenderLinkKeys keys)
				throws Exception {
			AsyncPrekeyStore store = new AsyncPrekeyStore(crypto,
					new InMemorySettingsManager(), new SystemClock());
			AsyncMeshDelivery.Identity id = newIdentity();
			sigPub = id.sigPub;
			List<AsyncPrekeyBundle.OneTimePrekey> otks =
					store.generateOneTimePrekeys(5);
			AsyncPrekeyStore.SignedPrekey spk = store.getSignedPrekey();
			bundle = AsyncPrekeyBundle.create(crypto, id.sigPub, id.sigPriv,
					id.agreePub, spk.id, spk.pub, spk.expiry, otks);
			delivery = new AsyncMeshDelivery(crypto, sealer, store,
					(senderPub, type, payload, ts) -> opened.add(payload),
					id, new SystemClock(), keys);
		}
	}
}
