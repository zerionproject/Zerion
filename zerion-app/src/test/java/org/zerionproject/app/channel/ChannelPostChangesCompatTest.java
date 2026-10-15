package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelPostChangesCompatTest {

	private final Random random = new Random(7713);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		owner = new ChannelTestNode(crypto, clock, network);
		nodes.add(owner);
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	@Test
	public void eachRequestVersionIsServedOnlyWhatItCanRead()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "as first written", 0L);
		owner.manager.publishPost(id, editMark(id, 0L, "corrected"), 0L);
		owner.manager.publishPost(id, "second", 0L);

		for (long v : new long[] {2L, 3L}) {
			List<ChannelPost> served = pull(id, v, null);
			assertEquals("version " + v, Collections.singletonList(2L),
					seqs(served));
		}
		List<ChannelPost> v4 = pull(id, 4L, null);
		assertEquals(Arrays.asList(1L, 2L), seqs(v4));
		List<ChannelPost> v5 = pull(id, 5L, null);
		assertEquals(Arrays.asList(0L, 1L, 2L), seqs(v5));
		assertTrue(v5.get(0).isErased());
		assertEquals("", v5.get(0).getBody());
		for (long v : new long[] {1L, 2L, 3L, 4L, 5L}) {
			assertFalse("version " + v, ChannelEditErasureTest.contains(
					pullReply(id, v, null), "as first written"
							.getBytes(StandardCharsets.UTF_8)));
		}
		for (List<ChannelPost> served : Arrays.asList(pull(id, 2L, null),
				pull(id, 3L, null), v4)) {
			for (ChannelPost p : served) assertFalse(p.isErased());
		}
	}

	@Test
	public void anEncryptedEditMarkIsKeptFromOlderSubscribersToo()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", false)
				.getChannelId();
		owner.manager.publishPost(id, "first", 0L);
		owner.manager.publishPost(id, editMark(id, 0L, "edited"), 0L);
		owner.manager.publishPost(id, "second", 0L);
		byte[] capability = owner.store.getChannel(id).getJoinCapability();

		assertEquals(Collections.singletonList(2L),
				seqs(pull(id, 2L, capability)));
	}

	@Test
	public void olderSubscribersSkipTheMarksAndReachTheNextPost()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "first", 0L);
		clock.advance(ChannelConstants.POST_EDIT_WINDOW_MS
				+ 2L * ChannelTestNode.HOUR);
		for (int i = 0; i < 120; i++) {
			owner.manager.publishPost(id, editMark(id, 0L, "edit " + i), 0L);
		}
		owner.manager.publishPost(id, "after the edits", 0L);

		List<ChannelPost> served = pull(id, 2L, null);

		assertEquals(Arrays.asList(0L, 121L), seqs(served));
	}

	@Test
	public void aReceivedDeletionRaisesNoUnreadCount() throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "kept", 0L);
		owner.manager.publishPost(id, "taken back", 0L);
		ChannelTestNode reader = join(id);
		reader.manager.markChannelRead(id);
		assertEquals(0, reader.manager.getUnreadCount(id));

		owner.manager.deletePost(id, 1L);
		reader.manager.refreshChannel(id);

		assertEquals(0, reader.manager.getUnreadCount(id));
		assertEquals(1, reader.manager.getRecentPosts(id, 10L).size());
	}

	@Test
	public void subscribersLearnThatTheOwnerTurnedDiscussionsOff()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "post", 0L);
		ChannelTestNode reader = join(id);
		assertTrue(reader.manager.areDiscussionsEnabled(id));

		owner.manager.setDiscussionsEnabled(id, false);
		reader.manager.refreshChannel(id);

		assertFalse(reader.manager.areDiscussionsEnabled(id));
		try {
			reader.manager.postComment(id, 0L, "anyone there?");
			fail("a comment was sent while discussions are off");
		} catch (DbException expected) {
		}

		owner.manager.setDiscussionsEnabled(id, true);
		reader.manager.refreshChannel(id);
		assertTrue(reader.manager.areDiscussionsEnabled(id));
	}

	@Test
	public void anEditorDeletesItsOwnPostForEveryoneWithAnOwnerSignedMark()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "from the owner", 0L);
		ChannelTestNode editor = editor(id);
		editor.manager.publishPost(id, "from the editor", 0L);
		ChannelTestNode reader = join(id);
		assertEquals(2, reader.manager.getRecentPosts(id, 10L).size());

		editor.manager.deletePost(id, 1L);
		reader.manager.refreshChannel(id);

		List<ChannelPost> shown = reader.manager.getRecentPosts(id, 10L);
		assertEquals(1, shown.size());
		assertEquals("from the owner", shown.get(0).getBody());
		ChannelPost mark = null;
		for (ChannelPost p : pull(id, 2L, null)) {
			if (p.getBody().startsWith(ChannelConstants.TOMBSTONE_PREFIX)) {
				mark = p;
			}
		}
		assertTrue("older subscribers receive the deletion", mark != null);
		assertFalse("signed by the channel, as releases from 3.0.15 expect",
				mark.signedByDelegate());
	}

	@Test
	public void anEditorCannotDeleteAPostItDidNotWrite() throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "from the owner", 0L);
		ChannelTestNode editor = editor(id);
		try {
			editor.manager.deletePost(id, 0L);
			fail("an editor deleted the owner's post");
		} catch (DbException expected) {
		}
		assertEquals(1, owner.manager.getRecentPosts(id, 10L).size());
	}

	@Test
	public void anEditorPostShapedLikeAnEditMarkIsRefused() throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "from the owner", 0L);
		ChannelTestNode editor = editor(id);
		try {
			editor.manager.publishPost(id, editMark(id, 0L, "rewritten"),
					0L);
			fail("an editor posted an edit mark");
		} catch (DbException expected) {
		}
		assertEquals(1, owner.store.getPosts(id).size());
	}

	private static String editMark(byte[] id, long target, String text) {
		return "ZRN_EDIT:" + ChannelStore.hex(id) + ":" + target + ":" + text;
	}

	private ChannelTestNode join(byte[] id) throws Exception {
		return join(id, crypto.generateHybridSignatureKeyPair());
	}

	private ChannelTestNode join(byte[] id, KeyPair identity)
			throws Exception {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, network, null,
				ChannelTestNode.identity(identity));
		nodes.add(n);
		String link = owner.manager.exportInviteLink(id);
		n.manager.joinChannel(n.manager.parseInviteLink(link));
		n.manager.refreshChannel(id);
		return n;
	}

	private ChannelTestNode editor(byte[] id) throws Exception {
		ChannelTestNode n = join(id);
		byte[] key = n.manager.getMyChannelPublicKey(id);
		owner.manager.delegatePublisher(id, Arrays.copyOfRange(key, 0, 32),
				Arrays.copyOfRange(key, 32, key.length), 0L);
		n.manager.refreshChannel(id);
		return n;
	}

	private List<ChannelPost> pull(byte[] id, long version,
			byte[] capability) throws Exception {
		return owner.pullCodec.decodePullResponse(
				pullReply(id, version, capability), id).newPosts;
	}

	private byte[] pullReply(byte[] id, long version,
			byte[] capability) throws Exception {
		ChannelHmacChallenge challenge = new ChannelHmacChallenge(crypto);
		byte[] nonce = capability == null ? null : challenge.freshNonce();
		byte[] request = owner.pullCodec.encodePullRequest(id, -1L,
				capability == null ? null
						: challenge.respond(capability, nonce, id), nonce);
		request = withVersion(request, version);
		if (capability != null) {
			request = owner.pullCodec.withProofV2(request,
					challenge.respondV2(capability, nonce, id,
							owner.pullCodec.withoutProof(request)));
		}
		return owner.handle(id, request);
	}

	private static List<Long> seqs(List<ChannelPost> posts) {
		List<Long> out = new ArrayList<>();
		for (ChannelPost p : posts) out.add(p.getSeqNum());
		return out;
	}

	private static byte[] withVersion(byte[] request, long version)
			throws Exception {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(request));
		BdfDictionary d = r.readDictionary();
		d.put("v", version);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}
}
