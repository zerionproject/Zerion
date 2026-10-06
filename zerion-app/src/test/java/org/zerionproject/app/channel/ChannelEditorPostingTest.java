package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelEditorPostingTest {

	private final Random random = new Random(187);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode publisher;
	private ChannelTestNode editor;
	private ChannelTestNode reader;
	private ChannelTestNode.TestChannel channel;
	private KeyPair editorIdentity;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		publisher.seedPublisher(channel, true, null, null, false,
				publisher.posts(channel, 2));
		editorIdentity = crypto.generateHybridSignatureKeyPair();
		editor = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId), null,
				identity(editorIdentity));
		editor.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());
		reader = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
		reader.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		editor.deleteFiles();
		reader.deleteFiles();
	}

	@Test
	public void aDelegatedEditorsPostReachesEverySubscriber()
			throws Exception {
		byte[] key = editorKey();
		publisher.manager.delegatePublisher(channel.channelId,
				Arrays.copyOfRange(key, 0, 32),
				Arrays.copyOfRange(key, 32, key.length), 0L);
		editor.manager.refreshChannel(channel.channelId);

		editor.manager.publishPost(channel.channelId, "from the editor", 0L);

		List<ChannelPost> held = publisher.store.getPosts(channel.channelId);
		assertEquals("the owner's device holds the editor's post", 3,
				held.size());
		assertTrue(held.get(2).signedByDelegate());
		reader.manager.refreshChannel(channel.channelId);
		List<ChannelPost> read = reader.manager.getRecentPosts(
				channel.channelId, 10L);
		assertEquals(3, read.size());
		assertEquals("from the editor", read.get(2).getBody());
		assertFalse(read.get(2).isWithheld());
	}

	@Test
	public void aRevokedEditorCanNoLongerPost() throws Exception {
		byte[] key = editorKey();
		publisher.manager.delegatePublisher(channel.channelId,
				Arrays.copyOfRange(key, 0, 32),
				Arrays.copyOfRange(key, 32, key.length), 0L);
		editor.manager.refreshChannel(channel.channelId);
		long seq = publisher.store.getChannel(channel.channelId)
				.getActiveDelegations().get(0).getDelegationSeq();
		publisher.manager.revokeDelegation(channel.channelId, seq);
		editor.manager.refreshChannel(channel.channelId);
		try {
			editor.manager.publishPost(channel.channelId, "too late", 0L);
			fail("a revoked editor posted");
		} catch (DbException expected) {
		}
		assertEquals(2, publisher.store.getPosts(channel.channelId).size());
	}

	private byte[] editorKey() throws Exception {
		try {
			Method m = editor.manager.getClass().getMethod(
					"getMyChannelPublicKey", byte[].class);
			return (byte[]) m.invoke(editor.manager, channel.channelId);
		} catch (NoSuchMethodException e) {
			return editorIdentity.getPublic().getEncoded();
		}
	}

	private static IdentityManager identity(KeyPair hybrid) {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) hybrid.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) hybrid.getPublic();
		LocalAuthor me = new LocalAuthor(new AuthorId(new byte[32]),
				Author.FORMAT_VERSION, "Editor", pub.getEd25519Component(),
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
