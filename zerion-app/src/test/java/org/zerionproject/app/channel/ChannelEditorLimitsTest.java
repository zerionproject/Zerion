package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelEditorLimitsTest {

	private static final long HOUR = ChannelTestNode.HOUR;
	private static final int PER_HOUR = (int) ChannelTestNode.constant(
			"MAX_EDITOR_POSTS_PER_HOUR", 30L);

	private final Random random = new Random(413);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode publisher;
	private ChannelTestNode editor;
	private ChannelTestNode reader;
	private ChannelTestNode.TestChannel channel;
	private KeyPair editorIdentity;
	private byte[] editorKey;

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
				ChannelTestNode.identity(editorIdentity));
		editor.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());
		reader = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
		reader.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());
		editorKey = editor.manager.getMyChannelPublicKey(channel.channelId);
		delegate();
		editor.manager.refreshChannel(channel.channelId);
		reader.manager.refreshChannel(channel.channelId);
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		editor.deleteFiles();
		reader.deleteFiles();
	}

	@Test
	public void anEditorCannotDeleteAPost() throws Exception {
		String mark = ChannelConstants.TOMBSTONE_PREFIX
				+ ChannelStore.hex(channel.channelId) + ":0:D";
		try {
			editor.manager.publishPost(channel.channelId, mark, 0L);
		} catch (DbException refused) {
		}

		reader.manager.refreshChannel(channel.channelId);
		List<ChannelPost> shown = reader.manager.getRecentPosts(
				channel.channelId, 10L);
		assertTrue("post 0 was deleted by the editor: " + bodies(shown),
				bodies(shown).contains("post 0"));
		assertTrue(reader.store.posts().meta(channel.channelId).holds(0L));
	}

	@Test
	public void anEditorsPostsAnHourAreBounded() throws Exception {
		for (int i = 0; i < PER_HOUR; i++) {
			editor.manager.publishPost(channel.channelId, "post " + i, 0L);
		}
		try {
			editor.manager.publishPost(channel.channelId, "one too many",
					0L);
			fail("the editor posted past its hourly allowance");
		} catch (DbException expected) {
		}
		assertEquals(2 + PER_HOUR,
				publisher.store.getPosts(channel.channelId).size());

		clock.advance(HOUR);
		editor.manager.publishPost(channel.channelId, "next hour", 0L);
		assertEquals(3 + PER_HOUR,
				publisher.store.getPosts(channel.channelId).size());
	}

	@Test
	public void theAllowanceOutlivesARestartOfTheOwnersDevice()
			throws Exception {
		for (int i = 0; i < PER_HOUR; i++) {
			editor.manager.publishPost(channel.channelId, "post " + i, 0L);
		}
		ChannelTestNode restarted = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport(), publisher);
		ChannelTestNode viaRestarted = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(restarted, channel.channelId), null,
				ChannelTestNode.identity(editorIdentity), editor.settings,
				editor.root);
		try {
			try {
				viaRestarted.manager.publishPost(channel.channelId,
						"after a restart", 0L);
				fail("a restart reset the editor's allowance");
			} catch (DbException expected) {
			}
		} finally {
			restarted.deleteFiles();
		}
	}

	@Test
	public void editorsPostsHeldByTheOwnerAreCappedInBytes()
			throws Exception {
		Field cap;
		try {
			cap = ChannelManagerImpl.class.getDeclaredField(
					"editorPostBytesCap");
		} catch (NoSuchFieldException e) {
			fail("the owner's device has no ceiling on editors' posts");
			return;
		}
		cap.setAccessible(true);
		cap.setLong(publisher.manager, 24_000L);
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 4000; i++) big.append('x');
		int accepted = 0;
		for (int i = 0; i < 40; i++) {
			try {
				editor.manager.publishPost(channel.channelId, big.toString(),
						0L);
				accepted++;
			} catch (DbException refused) {
				break;
			}
		}
		assertTrue("the owner's device held " + accepted
				+ " editor posts past the ceiling", accepted < 6);
		assertTrue(accepted >= 1);
	}

	@Test
	public void aBannedEditorCannotPost() throws Exception {
		publisher.manager.banSubscriber(channel.channelId,
				Arrays.copyOfRange(editorKey, 0, 32));

		try {
			editor.manager.publishPost(channel.channelId, "banned", 0L);
			fail("a banned editor posted");
		} catch (DbException expected) {
		}
		assertEquals(2, publisher.store.getPosts(channel.channelId).size());
		assertTrue("the ban left the editor's certificate active",
				publisher.store.getChannel(channel.channelId)
						.getActiveDelegations().isEmpty());
	}

	@Test
	public void anEditorGrantedAgainAfterARevocationCanPostAtOnce()
			throws Exception {
		long seq = publisher.store.getChannel(channel.channelId)
				.getActiveDelegations().get(0).getDelegationSeq();
		publisher.manager.revokeDelegation(channel.channelId, seq);
		clock.advance(HOUR);
		delegate();
		editor.manager.refreshChannel(channel.channelId);
		assertTrue(editor.manager.canPost(channel.channelId));

		editor.manager.publishPost(channel.channelId, "granted again", 0L);

		assertEquals(3, publisher.store.getPosts(channel.channelId).size());
		reader.manager.refreshChannel(channel.channelId);
		List<ChannelPost> shown = reader.manager.getRecentPosts(
				channel.channelId, 10L);
		assertTrue(bodies(shown).contains("granted again"));
	}

	private void delegate() throws Exception {
		publisher.manager.delegatePublisher(channel.channelId,
				Arrays.copyOfRange(editorKey, 0, 32),
				Arrays.copyOfRange(editorKey, 32, editorKey.length), 0L);
	}

	private static List<String> bodies(List<ChannelPost> posts) {
		List<String> out = new java.util.ArrayList<>();
		for (ChannelPost p : posts) out.add(p.getBody());
		return out;
	}

	@SuppressWarnings("unused")
	private static byte[] ed(KeyPair k) {
		return ((HybridSignaturePublicKey) k.getPublic())
				.getEd25519PublicKey();
	}
}
