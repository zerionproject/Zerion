package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelSubscriber;
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

public class ChannelSybilRetentionTest {

	private static final long HOUR = ChannelTestNode.HOUR;

	private final Random random = new Random(192);
	private CryptoComponent crypto;
	private ChannelTestNode publisher;
	private ChannelTestNode.TestChannel channel;
	private final List<KeyPair> known = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		publisher.seedPublisher(channel, true, null, null, false,
				publisher.posts(channel, 4));
		for (int i = 0; i < 5; i++) {
			KeyPair k = crypto.generateHybridSignatureKeyPair();
			known.add(k);
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) k.getPublic();
			publisher.subscriberStore.putSubscriber(channel.channelId,
					new ChannelSubscriber("member " + i,
							pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(),
							HOUR, false));
			trust(pub.getEd25519PublicKey());
		}
	}

	private void trust(byte[] ed) throws Exception {
		try {
			java.lang.reflect.Method m = ChannelSubscriberStore.class
					.getDeclaredMethod("setTrusted", byte[].class,
							byte[].class, boolean.class);
			m.setAccessible(true);
			m.invoke(publisher.subscriberStore, channel.channelId, ed, true);
		} catch (NoSuchMethodException ignored) {
		}
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
	}

	@Test
	public void knownSubscribersCommentsSurviveAFloodOfFreshKeys()
			throws Exception {
		for (int i = 0; i < 10; i++) {
			assertTrue(comment(known.get(i % 5), i % 4, "legit " + i));
		}
		List<KeyPair> sybils = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			sybils.add(crypto.generateHybridSignatureKeyPair());
		}
		for (int i = 0; i < 256; i++) {
			comment(sybils.get(i % 8), i % 4, "spam " + i);
		}
		int legit = 0;
		for (ChannelComment c
				: publisher.commentStore.getComments(channel.channelId)) {
			if (c.getBody().startsWith("legit")) legit++;
		}
		assertEquals("legitimate comments were erased", 10, legit);
		assertTrue("the next legitimate comment is refused",
				comment(known.get(0), 1, "still heard"));
	}

	@Test
	public void knownSubscribersReactionsSurviveAFloodOfFreshKeys()
			throws Exception {
		for (int i = 0; i < 5; i++) {
			assertTrue(react(known.get(i), i % 4, "+"));
		}
		for (int i = 0; i < 300; i++) {
			react(crypto.generateHybridSignatureKeyPair(), i % 4, "x");
		}
		int legit = 0;
		for (ChannelReaction r
				: publisher.reactionStore.getReactions(channel.channelId)) {
			if (r.getEmoji().equals("+")) legit++;
		}
		assertEquals("legitimate reactions were erased", 5, legit);
		assertTrue(react(known.get(0), 3, "!"));
	}

	@Test
	public void aKeyThatNeverAnnouncedCanBeBannedForGood() throws Exception {
		KeyPair troll = crypto.generateHybridSignatureKeyPair();
		assertTrue(comment(troll, 0, "troll"));
		byte[] ed = ((HybridSignaturePublicKey) troll.getPublic())
				.getEd25519PublicKey();

		publisher.manager.banSubscriber(channel.channelId, ed);

		for (ChannelComment c
				: publisher.commentStore.getComments(channel.channelId)) {
			assertFalse("the banned key's comment is still served",
					Arrays.equals(ed, c.getAuthorEd25519PubKey()));
		}
		assertFalse("the banned key can still comment",
				comment(troll, 1, "again"));
		ChannelSubscriberStore reopened = new ChannelSubscriberStore(
				publisher.settings, DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory(), DaggerChannelCodecTestComponent
				.create().getBdfWriterFactory());
		assertTrue("the ban outlives a restart",
				reopened.isBanned(channel.channelId, ed));
	}

	@Test
	public void announcedKeysTheOwnerDidNotVouchForAreAnonymous()
			throws Exception {
		for (int i = 0; i < 10; i++) {
			assertTrue(comment(known.get(i % 5), i % 4, "legit " + i));
		}
		List<KeyPair> sybils = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			KeyPair k = crypto.generateHybridSignatureKeyPair();
			sybils.add(k);
			assertTrue("the sybil could not announce itself",
					announce(k, "sybil " + i));
		}
		for (int i = 0; i < 280; i++) {
			comment(sybils.get(i % 40), i % 4, "spam " + i);
		}

		int legit = 0;
		for (ChannelComment c
				: publisher.commentStore.getComments(channel.channelId)) {
			if (c.getBody().startsWith("legit")) legit++;
		}
		assertEquals("self-announced keys erased the members' comments", 10,
				legit);
		assertTrue(comment(known.get(1), 2, "still heard"));
	}

	@Test
	public void aReactionCannotBeReplayedOverAChangeMadeInTheSameHour()
			throws Exception {
		KeyPair memberKeys = crypto.generateHybridSignatureKeyPair();
		ChannelTestNode member = new ChannelTestNode(crypto, publisher.clock,
				ChannelTestNode.servedBy(publisher, channel.channelId), null,
				ChannelTestNode.identity(memberKeys));
		try {
			member.seedSubscriber(channel, true, null, null,
					publisher.posts(channel, 4));
			member.manager.reactToPost(channel.channelId, 1L, "first");
			ChannelReaction first = null;
			for (ChannelReaction r
					: publisher.reactionStore.getReactions(channel.channelId)) {
				if (r.getEmoji().equals("first")) first = r;
			}
			assertTrue(first != null);

			member.manager.reactToPost(channel.channelId, 1L, "second");
			send(first);

			List<ChannelReaction> held =
					publisher.reactionStore.getReactions(channel.channelId);
			assertEquals(1, held.size());
			assertEquals("the earlier reaction was replayed over the change",
					"second", held.get(0).getEmoji());
		} finally {
			member.deleteFiles();
		}
	}

	private boolean announce(KeyPair key, String name) throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) key.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) key.getPublic();
		long ts = publisher.clock.currentTimeMillis() / HOUR * HOUR;
		byte[] sig = publisher.signatures.signUserAnnounce(
				publisher.codec.announceSignedInput(channel.channelId, name,
						ts), priv.getEd25519Component(),
				priv.getMlDsaPrivateKey());
		byte[] request = publisher.pullCodec.encodeAnnounceRequest(
				channel.channelId, name, ts, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), sig, null, null);
		return ChannelTestNode.ackOk(publisher.handle(channel.channelId,
				request));
	}

	@Test
	public void anOlderReactionCannotReplaceANewerOne() throws Exception {
		KeyPair k = known.get(0);
		ChannelReaction old = reaction(k, 1, "old");
		publisher.clock.advance(HOUR);
		assertTrue(react(k, 1, "new"));
		send(old);
		List<ChannelReaction> held =
				publisher.reactionStore.getReactions(channel.channelId);
		assertEquals(1, held.size());
		assertEquals("new", held.get(0).getEmoji());
	}

	private boolean comment(KeyPair author, long seq, String body)
			throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) author.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) author.getPublic();
		long ts = publisher.clock.currentTimeMillis() / HOUR * HOUR;
		long id = random.nextLong();
		byte[] sig = publisher.signatures.signUserComment(
				publisher.codec.commentSignedInput(channel.channelId, seq, id,
						body, "", ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		byte[] request = publisher.pullCodec.encodeCommentRequest(
				channel.channelId, seq, id, body, "", ts,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), sig, null,
				null);
		return ChannelTestNode.ackOk(publisher.handle(channel.channelId,
				request));
	}

	private boolean react(KeyPair signer, long seq, String emoji)
			throws Exception {
		return send(reaction(signer, seq, emoji));
	}

	private ChannelReaction reaction(KeyPair signer, long seq, String emoji)
			throws Exception {
		return publisher.reaction(channel, signer, seq, emoji);
	}

	private boolean send(ChannelReaction r) throws Exception {
		byte[] request = publisher.pullCodec.encodeReactionRequest(
				channel.channelId, r.getPostSeqNum(), r.getEmoji(),
				r.getTimestampHourMs(), r.getSignerEd25519PubKey(),
				r.getSignerMlDsaPubKey(), r.getSignature(), null, null);
		return ChannelTestNode.ackOk(publisher.handle(channel.channelId,
				request));
	}
}
