package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelDeltaSyncTest {

	private static final long HOUR = ChannelTestNode.HOUR;

	private final Random random = new Random(191);
	private CryptoComponent crypto;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;
	private ChannelTestNode.TestChannel channel;
	private final List<byte[]> responses = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		ChannelTestNode.MutableClock clock =
				new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		ChannelTransport served =
				ChannelTestNode.servedBy(publisher, channel.channelId);
		subscriber = new ChannelTestNode(crypto, clock,
				new ChannelTransport() {
					@Override
					public ChannelServer bindServer(byte[] id,
							@Nullable String key,
							ChannelRequestHandler handler)
							throws IOException {
						throw new IOException();
					}

					@Override
					public byte[] requestFromOnion(String onion,
							byte[] request) throws IOException {
						byte[] r = served.requestFromOnion(onion, request);
						responses.add(r);
						return r;
					}

					@Override
					public boolean isReachable(String onion) {
						return true;
					}
				});
		publisher.seedPublisher(channel, true, null, null, false,
				publisher.posts(channel, 2));
		List<ChannelReaction> reactions = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			reactions.add(publisher.reaction(channel,
					crypto.generateHybridSignatureKeyPair(), i % 2, "+"));
		}
		publisher.reactionStore.setReactions(channel.channelId, reactions);
		List<ChannelComment> comments = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			comments.add(comment(crypto.generateHybridSignatureKeyPair(),
					i % 2, "comment " + i));
		}
		publisher.commentStore.setComments(channel.channelId, comments);
		subscriber.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	@Test
	public void aPullOfAnUnchangedChannelCarriesNoReactionsOrComments()
			throws Exception {
		subscriber.manager.refreshChannel(channel.channelId);
		assertEquals(8, subscriber.reactionStore
				.getReactions(channel.channelId).size());
		assertEquals(8, subscriber.commentStore
				.getComments(channel.channelId).size());
		int first = responses.get(responses.size() - 1).length;

		subscriber.manager.refreshChannel(channel.channelId);

		byte[] second = responses.get(responses.size() - 1);
		ChannelPullCodec.PullResponse r =
				publisher.pullCodec.decodePullResponse(second,
						channel.channelId);
		assertEquals("unchanged reactions were sent again", 0,
				r.reactions.size());
		assertEquals("unchanged comments were sent again", 0,
				r.comments.size());
		assertTrue(second.length * 4 < first);
		assertEquals(8, subscriber.reactionStore
				.getReactions(channel.channelId).size());
		assertEquals(8, subscriber.commentStore
				.getComments(channel.channelId).size());
	}

	@Test
	public void aPullAfterOneNewReactionCarriesThatOne() throws Exception {
		subscriber.manager.refreshChannel(channel.channelId);
		List<ChannelReaction> more = new ArrayList<>(publisher.reactionStore
				.getReactions(channel.channelId));
		more.add(publisher.reaction(channel,
				crypto.generateHybridSignatureKeyPair(), 1L, "*"));
		publisher.reactionStore.setReactions(channel.channelId, more);

		subscriber.manager.refreshChannel(channel.channelId);

		ChannelPullCodec.PullResponse r =
				publisher.pullCodec.decodePullResponse(
						responses.get(responses.size() - 1),
						channel.channelId);
		assertEquals(1, r.reactions.size());
		assertEquals("*", r.reactions.get(0).getEmoji());
		assertEquals(9, subscriber.reactionStore
				.getReactions(channel.channelId).size());
	}

	@Test
	public void aReactionThePublisherLetGoLeavesTheSubscriberToo()
			throws Exception {
		subscriber.manager.refreshChannel(channel.channelId);
		List<ChannelReaction> fewer = new ArrayList<>(publisher.reactionStore
				.getReactions(channel.channelId));
		ChannelReaction gone = fewer.remove(0);
		publisher.reactionStore.setReactions(channel.channelId, fewer);

		subscriber.manager.refreshChannel(channel.channelId);

		List<ChannelReaction> held =
				subscriber.reactionStore.getReactions(channel.channelId);
		assertEquals(7, held.size());
		for (ChannelReaction r : held) {
			assertTrue(!java.util.Arrays.equals(r.getSignature(),
					gone.getSignature()));
		}
	}

	@Test
	public void eachChannelsRevisionsAreKeptApart() throws Exception {
		ChannelTestNode.TestChannel other =
				new ChannelTestNode.TestChannel(crypto, random);
		publisher.seedPublisher(other, true, null, null, false,
				publisher.posts(other, 1));
		publisher.reactionStore.setReactions(other.channelId,
				Collections.singletonList(publisher.reaction(other,
						crypto.generateHybridSignatureKeyPair(), 0L, "+")));
		subscriber.manager.refreshChannel(channel.channelId);

		assertTrue("every channel's revisions are kept in one value",
				publisher.settings.getSettings("zerion-channels-item-sync")
						.isEmpty());
		String a = ChannelStore.hex(channel.channelId);
		String b = ChannelStore.hex(other.channelId);
		assertTrue(!publisher.settings.getSettings(
				"zerion-channels-item-sync:" + a).isEmpty());
		assertTrue(!publisher.settings.getSettings(
				"zerion-channels-item-sync:" + b).isEmpty());
		assertTrue("every channel's cursors are kept in one value",
				subscriber.settings.getSettings("zerion-channels-sync-cursor")
						.isEmpty());
		assertTrue(!subscriber.settings.getSettings(
				"zerion-channels-sync-cursor:" + a).isEmpty());
	}

	@Test
	public void aClientWithoutAVersionStillGetsTheWholeSets()
			throws Exception {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_PULL_REQUEST);
		d.put("channelId", channel.channelId);
		d.put("sinceSeqNum", 1L);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		for (int i = 0; i < 2; i++) {
			ChannelPullCodec.PullResponse r = publisher.pullCodec
					.decodePullResponse(publisher.handle(channel.channelId,
							out.toByteArray()), channel.channelId);
			assertEquals(8, r.reactions.size());
			assertEquals(8, r.comments.size());
		}
	}

	private ChannelComment comment(KeyPair author, long seq, String body)
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
		return new ChannelComment(seq, id, body, "",
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), ts, sig);
	}
}
