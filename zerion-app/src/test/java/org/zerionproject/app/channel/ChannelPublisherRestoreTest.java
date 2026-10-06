package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelPublisherRestoreTest {

	private static final long HOUR = ChannelTestNode.HOUR;

	private final Random random = new Random(412);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		publisher = node(new ChannelTestNode.MemorySettings());
		subscriber = node(new ChannelTestNode.MemorySettings());
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	@Test
	public void aPublisherRestoredFromABackupKeepsItsSubscribersReceiving()
			throws Exception {
		byte[] id = create();
		publisher.manager.publishPost(id, "p0", 0L);
		publisher.manager.publishPost(id, "p1", 0L);
		subscriber.manager.refreshChannel(id);
		assertEquals(2, subscriber.store.getPosts(id).size());
		publisher.callIfPresent("ensureInstanceChecked", new Class<?>[0]);
		ChannelTestNode.MemorySettings backup = publisher.settings.snapshot();
		publisher.manager.publishPost(id, "p2", 0L);
		subscriber.manager.refreshChannel(id);
		assertEquals(3, subscriber.store.getPosts(id).size());

		ChannelTestNode restored = restoreFrom(backup);
		restored.manager.publishPost(id, "p2 again", 0L);
		restored.manager.publishPost(id, "p3", 0L);
		subscriber.manager.refreshChannel(id);

		List<String> bodies = bodies(subscriber, id);
		assertTrue("the subscriber stalled: " + bodies,
				bodies.contains("p3"));
		assertTrue(bodies.contains("p2 again"));
		restored.manager.publishPost(id, "p4", 0L);
		subscriber.manager.refreshChannel(id);
		assertTrue(bodies(subscriber, id).contains("p4"));
	}

	@Test
	public void aRestoredPublishersReactionsDoNotDriftFromItsSubscribers()
			throws Exception {
		byte[] id = create();
		publisher.manager.publishPost(id, "p0", 0L);
		subscriber.manager.refreshChannel(id);
		KeyPair a = crypto.generateHybridSignatureKeyPair();
		KeyPair b = crypto.generateHybridSignatureKeyPair();
		KeyPair c = crypto.generateHybridSignatureKeyPair();
		ChannelReaction ra = reaction(id, a, "a");
		ChannelReaction rb = reaction(id, b, "b");
		ChannelReaction rc = reaction(id, c, "c");
		publisher.reactionStore.setReactions(id, Arrays.asList(ra));
		subscriber.manager.refreshChannel(id);
		assertEquals(1, subscriber.reactionStore.getReactions(id).size());
		publisher.callIfPresent("ensureInstanceChecked", new Class<?>[0]);
		ChannelTestNode.MemorySettings backup = publisher.settings.snapshot();
		publisher.reactionStore.setReactions(id, Arrays.asList(ra, rb));
		subscriber.manager.refreshChannel(id);
		assertEquals(2, subscriber.reactionStore.getReactions(id).size());

		ChannelTestNode restored = restoreFrom(backup);
		restored.reactionStore.setReactions(id, Arrays.asList(ra, rc));
		subscriber.manager.refreshChannel(id);

		List<String> held = new ArrayList<>();
		for (ChannelReaction r : subscriber.reactionStore.getReactions(id)) {
			held.add(r.getEmoji());
		}
		assertTrue("the subscriber drifted from the publisher: " + held,
				held.contains("c"));
		assertFalse(held.contains("b"));
	}

	private byte[] create() throws Exception {
		ChannelState s = publisher.manager.createChannel("name", "about",
				true);
		byte[] id = s.getChannelId();
		String link = publisher.manager.exportInviteLink(id);
		subscriber.manager.joinChannel(
				subscriber.manager.parseInviteLink(link));
		subscriber.manager.refreshChannel(id);
		return id;
	}

	private ChannelTestNode restoreFrom(ChannelTestNode.MemorySettings backup)
			throws Exception {
		network.unbindAll();
		ChannelTestNode restored = node(backup);
		restored.call("rebindOwnedChannelsOnStartup", new Class<?>[0]);
		return restored;
	}

	private ChannelTestNode node(ChannelTestNode.MemorySettings settings) {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, network,
				settings);
		nodes.add(n);
		return n;
	}

	private static List<String> bodies(ChannelTestNode node, byte[] id)
			throws Exception {
		List<String> out = new ArrayList<>();
		for (ChannelPost p : node.store.getPosts(id)) out.add(p.getBody());
		return out;
	}

	private ChannelReaction reaction(byte[] channelId, KeyPair signer,
			String emoji) throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		byte[] sig = publisher.signatures.signUserReaction(
				publisher.codec.reactionSignedInput(channelId, 0L, emoji, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		return new ChannelReaction(0L, emoji, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), ts, sig);
	}
}
