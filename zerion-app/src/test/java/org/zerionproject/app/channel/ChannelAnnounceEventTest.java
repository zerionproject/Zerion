package org.zerionproject.app.channel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.event.Event;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelAnnounceEventTest {

	private final Random random = new Random(907);
	private CryptoComponent crypto;
	private ChannelTestNode node;
	private ChannelTestNode.TestChannel open;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		node = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		open = new ChannelTestNode.TestChannel(crypto, random);
		node.seedPublisher(open, true, null, null, false,
				node.posts(open, 1));
	}

	@After
	public void tearDown() {
		node.deleteFiles();
	}

	@Test
	public void aSubscriberRenamingItselfCannotFloodTheOwnerScreen()
			throws Exception {
		KeyPair signer = crypto.generateHybridSignatureKeyPair();
		int before = manifestEvents();
		assertTrue(announce(signer, "Ann"));
		assertEquals("a new subscriber refreshes the screen", before + 1,
				manifestEvents());
		assertTrue(announce(signer, "Ann"));
		assertEquals("the same name again changes nothing", before + 1,
				manifestEvents());
		int scheduledBefore = node.scheduledDelays.size();
		for (int i = 0; i < 40; i++) announce(signer, "Ann" + i);
		assertTrue("renames are coalesced, saw "
				+ (manifestEvents() - before),
				manifestEvents() - before <= 2);
		assertTrue("at most one late refresh is scheduled",
				node.scheduledDelays.size() - scheduledBefore <= 1);
	}

	private int manifestEvents() {
		int n = 0;
		synchronized (node.events) {
			for (Event e : node.events) {
				if (e instanceof ChannelStateChangedEvent
						&& ((ChannelStateChangedEvent) e).getKind()
						== ChannelStateChangedEvent.Kind.MANIFEST_UPDATED
						&& Arrays.equals(open.channelId,
						((ChannelStateChangedEvent) e).getChannelId())) {
					n++;
				}
			}
		}
		return n;
	}

	private boolean announce(KeyPair signer, String name) throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = node.clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
		byte[] sig = node.signatures.signUserAnnounce(
				node.codec.announceSignedInput(open.channelId, name, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		byte[] request = node.pullCodec.encodeAnnounceRequest(open.channelId,
				name, ts, pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(),
				sig, null, null);
		return ChannelTestNode.ackOk(node.handle(open.channelId, request));
	}
}
