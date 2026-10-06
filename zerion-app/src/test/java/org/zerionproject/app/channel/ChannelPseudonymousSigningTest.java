package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ChannelPseudonymousSigningTest {

	private final Random random = new Random(197);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private KeyPair identity;
	private final ChannelTestNode[] nodes = new ChannelTestNode[3];

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		identity = crypto.generateHybridSignatureKeyPair();
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) if (n != null) n.deleteFiles();
	}

	@Test
	public void aReactionAndACommentCarryAChannelKeyNotTheIdentity()
			throws Exception {
		ChannelTestNode.TestChannel a = channel();
		ChannelTestNode publisher = publisher(a, 0);
		ChannelTestNode member = member(publisher, a, 2);

		member.manager.reactToPost(a.channelId, 0L, "+");
		member.manager.postComment(a.channelId, 0L, "hello");

		byte[] idEd = ((HybridSignaturePublicKey) identity.getPublic())
				.getEd25519PublicKey();
		ChannelReaction r = publisher.reactionStore
				.getReactions(a.channelId).get(0);
		ChannelComment c = publisher.commentStore
				.getComments(a.channelId).get(0);
		assertFalse("the reaction carries the identity key",
				Arrays.equals(idEd, r.getSignerEd25519PubKey()));
		assertFalse("the comment carries the identity key",
				Arrays.equals(idEd, c.getAuthorEd25519PubKey()));
		assertArrayEquals(r.getSignerEd25519PubKey(),
				c.getAuthorEd25519PubKey());
		assertFalse("the comment carries the account name",
				"Member".equals(c.getAuthorDisplayName()));
	}

	@Test
	public void twoChannelsSeeTwoUnrelatedKeys() throws Exception {
		ChannelTestNode.TestChannel a = channel();
		ChannelTestNode publisherA = publisher(a, 0);
		ChannelTestNode memberA = member(publisherA, a, 2);
		memberA.manager.reactToPost(a.channelId, 0L, "+");
		byte[] inA = publisherA.reactionStore.getReactions(a.channelId)
				.get(0).getSignerEd25519PubKey();
		memberA.deleteFiles();

		ChannelTestNode.TestChannel b = channel();
		ChannelTestNode publisherB = publisher(b, 1);
		ChannelTestNode memberB = member(publisherB, b, 2);
		memberB.manager.reactToPost(b.channelId, 0L, "+");
		byte[] inB = publisherB.reactionStore.getReactions(b.channelId)
				.get(0).getSignerEd25519PubKey();
		assertFalse("two channels link the same member",
				Arrays.equals(inA, inB));

		memberB.manager.reactToPost(b.channelId, 1L, "*");
		for (ChannelReaction r
				: publisherB.reactionStore.getReactions(b.channelId)) {
			assertArrayEquals("one channel sees one key", inB,
					r.getSignerEd25519PubKey());
		}
	}

	@Test
	public void theOwnerCommentsWithTheChannelKey() throws Exception {
		ChannelTestNode.TestChannel a = channel();
		ChannelTestNode publisher = publisher(a, 0);

		publisher.manager.postComment(a.channelId, 0L, "from the owner");

		ChannelComment c = publisher.commentStore.getComments(a.channelId)
				.get(0);
		assertArrayEquals(a.ed(), c.getAuthorEd25519PubKey());
		assertArrayEquals(a.ml(), c.getAuthorMlDsaPubKey());
		assertEquals("", c.getAuthorDisplayName());
	}

	private ChannelTestNode.TestChannel channel() {
		return new ChannelTestNode.TestChannel(crypto, random);
	}

	private ChannelTestNode publisher(ChannelTestNode.TestChannel c, int slot)
			throws Exception {
		ChannelTestNode p = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport(), null,
				identity(crypto.generateHybridSignatureKeyPair(), "Owner"));
		nodes[slot] = p;
		List<ChannelPost> posts = p.posts(c, 2);
		p.seedPublisher(c, true, null, null, false, posts);
		return p;
	}

	private ChannelTestNode member(ChannelTestNode publisher,
			ChannelTestNode.TestChannel c, int slot) throws Exception {
		ChannelTestNode m = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, c.channelId), null,
				identity(identity, "Member"));
		nodes[slot] = m;
		m.seedSubscriber(c, true, null, null,
				publisher.store.getPosts(c.channelId));
		return m;
	}

	private static IdentityManager identity(KeyPair hybrid, String name) {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) hybrid.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) hybrid.getPublic();
		LocalAuthor me = new LocalAuthor(new AuthorId(new byte[32]),
				Author.FORMAT_VERSION, name, pub.getEd25519Component(),
				priv.getEd25519Component());
		return (IdentityManager) Proxy.newProxyInstance(
				IdentityManager.class.getClassLoader(),
				new Class<?>[] {IdentityManager.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "getLocalAuthor":
							return me;
						case "getLocalMlDsaSigPublicKey":
							return pub.getMlDsaPublicKey();
						case "getLocalMlDsaSigPrivateKey":
							return priv.getMlDsaPrivateKey();
						default:
							Class<?> r = method.getReturnType();
							if (r == boolean.class) return false;
							if (r == int.class) return 0;
							if (r == long.class) return 0L;
							return null;
					}
				});
	}
}
