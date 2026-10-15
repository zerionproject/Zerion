package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelPostTooLongException;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelPostChangesTest {

	private final Random random = new Random(9081);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;
	@Nullable
	private volatile Tap tap;

	private interface Tap {
		byte[] onRequest(byte[] request) throws Exception;
	}

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
	public void anEditReplacesTheTextEverywhereAndIsMarkedEdited()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "first draft", 0L);
		owner.manager.publishPost(id, "unrelated", 0L);
		ChannelTestNode reader = join(id);

		owner.manager.editPost(id, 0L, "final text");
		reader.manager.refreshChannel(id);

		for (ChannelTestNode n : Arrays.asList(owner, reader)) {
			List<ChannelPost> shown = n.manager.getRecentPosts(id, 10L);
			assertEquals(Arrays.asList("final text", "unrelated"),
					bodies(shown));
			assertTrue(shown.get(0).isEdited());
			assertFalse(shown.get(1).isEdited());
			assertEquals(0L, shown.get(0).getSeqNum());
		}
	}

	@Test
	public void theNewestPostStaysListedAfterAnEditOrADeletion()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "older", 0L);
		owner.manager.publishPost(id, "newest", 0L);

		owner.manager.editPost(id, 1L, "newest, edited");
		List<ChannelPost> latest = owner.manager.getRecentPosts(id, 1L);
		assertEquals(Collections.singletonList("newest, edited"),
				bodies(latest));
		assertTrue(latest.get(0).isEdited());

		owner.manager.deletePost(id, 1L);
		assertEquals(Collections.singletonList("older"),
				bodies(owner.manager.getRecentPosts(id, 1L)));
		assertTrue(owner.manager.getRecentPosts(id, 0L).isEmpty());
	}

	@Test
	public void aLaterEditReplacesTheEarlierOneAndLeavesNoHistory()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "original", 0L);
		ChannelTestNode reader = join(id);
		owner.manager.editPost(id, 0L, "first correction");
		reader.manager.refreshChannel(id);
		owner.manager.editPost(id, 0L, "second correction");
		reader.manager.refreshChannel(id);
		ChannelTestNode late = join(id);

		for (ChannelTestNode n : Arrays.asList(owner, reader, late)) {
			assertEquals(Collections.singletonList("second correction"),
					bodies(n.manager.getRecentPosts(id, 10L)));
			for (ChannelPost held : n.store.getPosts(id)) {
				assertFalse(held.getBody().contains("first correction"));
			}
		}
	}

	@Test
	public void anEditOfAPrivatePostTravelsEncrypted() throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, "secret draft", 0L);
		ChannelTestNode reader = join(id);

		owner.manager.editPost(id, 0L, "secret final");
		reader.manager.refreshChannel(id);

		assertEquals(Collections.singletonList("secret final"),
				bodies(reader.manager.getRecentPosts(id, 10L)));
		for (ChannelPost held : owner.store.getPosts(id)) {
			assertFalse(held.getBody().startsWith(
					ChannelConstants.EDIT_PREFIX));
			assertFalse(held.getBody().contains("secret"));
		}
	}

	@Test
	public void aPostCanBeEditedOnlyWithinADay() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "written", 0L);
		clock.advance(ChannelConstants.POST_EDIT_WINDOW_MS);
		owner.manager.editPost(id, 0L, "still in time");
		clock.advance(2L * ChannelTestNode.HOUR);
		try {
			owner.manager.editPost(id, 0L, "too late");
			fail("a post was edited after the window");
		} catch (DbException expected) {
		}
		assertEquals(Collections.singletonList("still in time"),
				bodies(owner.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void subscribersIgnoreAnEditMarkMadeAfterTheWindow()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "as written", 0L);
		ChannelTestNode reader = join(id);
		clock.advance(ChannelConstants.POST_EDIT_WINDOW_MS
				+ 2L * ChannelTestNode.HOUR);
		owner.manager.publishPost(id, ChannelPostMarkers.edit(id, 0L,
				"rewritten later"), 0L);
		reader.manager.refreshChannel(id);

		List<ChannelPost> shown = reader.manager.getRecentPosts(id, 10L);
		assertEquals(Collections.singletonList("as written"), bodies(shown));
		assertFalse(shown.get(0).isEdited());
	}

	@Test
	public void anEditOfAnEphemeralPostExpiresWithIt() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "short lived", 3600L);
		owner.manager.editPost(id, 0L, "short lived, corrected");
		ChannelPost mark = owner.store.getPosts(id).get(1);
		ChannelPost target = owner.store.getPosts(id).get(0);
		assertEquals(target.getTimestampHourMs() + target.getTtlMs(),
				mark.getTimestampHourMs() + mark.getTtlMs());
	}

	@Test
	public void anEditTooLongForAPostIsRefused() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "short", 0L);
		char[] text = new char[ChannelConstants.MAX_POST_BODY_CHARS];
		Arrays.fill(text, 'x');
		try {
			owner.manager.editPost(id, 0L, new String(text));
			fail("an edit longer than a post was published");
		} catch (ChannelPostTooLongException expected) {
		}
	}

	@Test
	public void deletingAnEditedPostRemovesItsEditsToo() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "original", 0L);
		owner.manager.publishPost(id, "other", 0L);
		ChannelTestNode reader = join(id);
		owner.manager.editPost(id, 0L, "edited words");
		reader.manager.refreshChannel(id);

		owner.manager.deletePost(id, 0L);
		reader.manager.refreshChannel(id);

		for (ChannelTestNode n : Arrays.asList(owner, reader)) {
			assertEquals(Collections.singletonList("other"),
					bodies(n.manager.getRecentPosts(id, 10L)));
			for (ChannelPost held : n.store.getPosts(id)) {
				assertFalse(held.getBody().contains("edited words"));
			}
		}
	}

	@Test
	public void anEditorEditsItsOwnPostButNotOthers() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "by the owner", 0L);
		ChannelTestNode editor = editor(id);
		editor.manager.publishPost(id, "by the editor", 0L);
		ChannelTestNode reader = join(id);

		editor.manager.editPost(id, 1L, "by the editor, fixed");
		reader.manager.refreshChannel(id);
		List<ChannelPost> shown = reader.manager.getRecentPosts(id, 10L);
		assertEquals(Arrays.asList("by the owner", "by the editor, fixed"),
				bodies(shown));
		assertTrue(shown.get(1).isEdited());
		assertTrue(shown.get(1).signedByDelegate());

		try {
			editor.manager.editPost(id, 0L, "hijacked");
			fail("an editor edited the owner's post");
		} catch (DbException expected) {
		}
		assertEquals(ChannelConstants.SUBMIT_STATUS_REFUSED,
				sendChange(editor, id, 0L, ChannelConstants.CHANGE_EDIT,
						0L, "hijacked", false));
		assertEquals("by the owner",
				owner.manager.getRecentPosts(id, 10L).get(0).getBody());
	}

	@Test
	public void aReplayedEditorEditIsStaleOnceTheTextMovedOn()
			throws Exception {
		byte[] id = create(true);
		ChannelTestNode editor = editor(id);
		editor.manager.publishPost(id, "draft", 0L);
		List<byte[]> sent = new ArrayList<>();
		tap = request -> {
			if (ChannelConstants.WIRE_TYPE_CHANGE_POST.equals(
					owner.pullCodec.peekType(request))) {
				sent.add(request);
			}
			return request;
		};
		editor.manager.editPost(id, 0L, "first fix");
		editor.manager.editPost(id, 0L, "second fix");
		tap = null;
		assertEquals(2, sent.size());

		byte[] ack = owner.handle(id, sent.get(0));

		assertEquals(ChannelConstants.SUBMIT_STATUS_STALE,
				owner.pullCodec.decodeChangePostAck(ack));
		assertEquals(Collections.singletonList("second fix"),
				bodies(owner.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void aRevokedEditorCanNoLongerChangeItsPosts() throws Exception {
		byte[] id = create(true);
		ChannelTestNode editor = editor(id);
		editor.manager.publishPost(id, "by the editor", 0L);
		long seq = owner.store.getChannel(id).getActiveDelegations().get(0)
				.getDelegationSeq();
		owner.manager.revokeDelegation(id, seq);
		editor.manager.refreshChannel(id);
		try {
			editor.manager.setPostCommentsEnabled(id, 0L, false);
			fail("a revoked editor changed its post");
		} catch (DbException expected) {
		}
		assertTrue(owner.manager.areCommentsEnabled(id, 0L));
	}

	@Test
	public void commentsTurnedOffForOnePostAreRefusedThereOnly()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "quiet post", 0L);
		owner.manager.publishPost(id, "open post", 0L);
		ChannelTestNode reader = join(id);

		owner.manager.setPostCommentsEnabled(id, 0L, false);
		reader.manager.refreshChannel(id);

		assertTrue(reader.manager.areDiscussionsEnabled(id));
		assertFalse(reader.manager.areCommentsEnabled(id, 0L));
		assertTrue(reader.manager.areCommentsEnabled(id, 1L));
		assertEquals(Collections.singleton(0L),
				reader.manager.getCommentExceptions(id));
		try {
			reader.manager.postComment(id, 0L, "let me in");
			fail("a comment went out on a post with comments off");
		} catch (DbException expected) {
		}
		assertFalse(ChannelTestNode.ackOk(owner.handle(id,
				commentRequest(id, reader, 0L, "around it"))));
		reader.manager.postComment(id, 1L, "this one is fine");
		assertEquals(1, owner.manager.getAllComments(id).size());

		owner.manager.setPostCommentsEnabled(id, 0L, true);
		reader.manager.refreshChannel(id);
		assertTrue(reader.manager.areCommentsEnabled(id, 0L));
	}

	@Test
	public void anOpenFeedHearsWhenAPostCommentSettingArrives()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "a post", 0L);
		ChannelTestNode reader = join(id);
		reader.manager.refreshChannel(id);
		reader.events.clear();
		reader.manager.refreshChannel(id);
		assertEquals(0, settingEvents(reader, id));

		owner.manager.setPostCommentsEnabled(id, 0L, false);
		reader.manager.refreshChannel(id);
		assertEquals(1, settingEvents(reader, id));

		reader.events.clear();
		reader.manager.refreshChannel(id);
		assertEquals(0, settingEvents(reader, id));
	}

	@Test
	public void theChannelSwitchClearsThePostExceptions() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "a", 0L);
		owner.manager.publishPost(id, "b", 0L);
		ChannelTestNode reader = join(id);
		owner.manager.setPostCommentsEnabled(id, 0L, false);

		owner.manager.setDiscussionsEnabled(id, false);
		owner.manager.setPostCommentsEnabled(id, 1L, true);
		reader.manager.refreshChannel(id);

		assertFalse(reader.manager.areDiscussionsEnabled(id));
		assertFalse(reader.manager.areCommentsEnabled(id, 0L));
		assertTrue(reader.manager.areCommentsEnabled(id, 1L));

		owner.manager.setDiscussionsEnabled(id, true);
		reader.manager.refreshChannel(id);
		assertTrue(reader.manager.areCommentsEnabled(id, 0L));
		assertTrue(reader.manager.areCommentsEnabled(id, 1L));
	}

	@Test
	public void aCommentSettingAlteredOnTheWayFailsTheReply()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "a", 0L);
		ChannelTestNode reader = join(id);
		owner.manager.setPostCommentsEnabled(id, 0L, false);
		replyEdit = reply -> {
			BdfDictionary d = read(reply);
			BdfDictionary ds = d.getDictionary("ds");
			ds.put("x", new BdfList());
			d.put("ds", ds);
			return write(d);
		};
		try {
			reader.manager.refreshChannel(id);
			fail("an altered comment setting was taken");
		} catch (DbException expected) {
		}
		assertTrue(reader.manager.areCommentsEnabled(id, 0L));
		replyEdit = reply -> {
			BdfDictionary d = read(reply);
			d.remove("ds");
			return write(d);
		};
		try {
			reader.manager.refreshChannel(id);
			fail("a version 4 reply without its comment setting was taken");
		} catch (DbException expected) {
		}
		replyEdit = null;
		reader.manager.refreshChannel(id);
		assertFalse(reader.manager.areCommentsEnabled(id, 0L));
	}

	@Test
	public void aCommentSettingThatIsNotAPlainListOfPostsIsRefused()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "a", 0L);
		byte[] reply = owner.handle(id,
				owner.pullCodec.encodePullRequest(id, -1L, null, null));
		List<BdfList> bad = new ArrayList<>();
		bad.add(BdfList.of(0L, 0L));
		bad.add(BdfList.of(-1L));
		bad.add(BdfList.of("0"));
		for (BdfList x : bad) {
			BdfDictionary d = read(reply);
			BdfDictionary ds = d.getDictionary("ds");
			ds.put("x", x);
			d.put("ds", ds);
			try {
				owner.pullCodec.decodePullResponse(write(d), id);
				fail("a malformed comment setting was decoded");
			} catch (FormatException expected) {
			}
		}
	}

	@Test
	public void anOlderOwnerReleaseLeavesTheCommentEntryShown()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "a", 0L);
		ChannelTestNode reader = join(id);
		owner.manager.setPostCommentsEnabled(id, 0L, false);
		reader.manager.refreshChannel(id);
		assertFalse(reader.manager.areCommentsEnabled(id, 0L));

		tap = request -> ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(
				owner.pullCodec.peekType(request))
				? withVersion(request, 3L) : request;
		reader.manager.refreshChannel(id);

		assertTrue(reader.manager.areCommentsEnabled(id, 0L));
		assertTrue(reader.manager.getCommentExceptions(id).isEmpty());
	}

	@Test
	public void anEditorTurnsCommentsOffOnItsOwnPostOnly() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "by the owner", 0L);
		ChannelTestNode editor = editor(id);
		editor.manager.publishPost(id, "by the editor", 0L);

		editor.manager.setPostCommentsEnabled(id, 1L, false);

		assertFalse(owner.manager.areCommentsEnabled(id, 1L));
		assertFalse(editor.manager.areCommentsEnabled(id, 1L));
		try {
			editor.manager.setPostCommentsEnabled(id, 0L, false);
			fail("an editor turned comments off on the owner's post");
		} catch (DbException expected) {
		}
		assertTrue(owner.manager.areCommentsEnabled(id, 0L));
	}

	@Test
	public void liftingABanLetsTheKeyCommentAgainButRestoresNothing()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "post", 0L);
		ChannelTestNode reader = join(id);
		reader.manager.postComment(id, 0L, "before the ban");
		byte[] key = Arrays.copyOf(reader.manager.getMyChannelPublicKey(id),
				32);

		owner.manager.banSubscriber(id, key);
		assertTrue(owner.manager.getAllComments(id).isEmpty());
		assertEquals(1, owner.manager.getBannedKeys(id).size());
		try {
			reader.manager.postComment(id, 0L, "while banned");
			fail("a banned key commented");
		} catch (DbException expected) {
		}

		owner.manager.unbanSubscriber(id, key);
		assertTrue(owner.manager.getBannedKeys(id).isEmpty());
		reader.manager.postComment(id, 0L, "after the ban");

		List<String> held = new ArrayList<>();
		for (ChannelComment c : owner.manager.getAllComments(id)) {
			held.add(c.getBody());
		}
		assertEquals(Collections.singletonList("after the ban"), held);
	}

	@Test
	public void liftingABanDoesNotGiveBackAnEditorRole() throws Exception {
		byte[] id = create(true);
		ChannelTestNode editor = editor(id);
		byte[] key = Arrays.copyOf(editor.manager.getMyChannelPublicKey(id),
				32);

		owner.manager.banSubscriber(id, key);
		owner.manager.unbanSubscriber(id, key);
		editor.manager.refreshChannel(id);

		assertTrue(owner.manager.listActiveDelegations(id).isEmpty());
		assertFalse(editor.manager.canPost(id));
	}

	@Test
	public void onlyTheOwnerLiftsABan() throws Exception {
		byte[] id = create(true);
		ChannelTestNode reader = join(id);
		try {
			reader.manager.unbanSubscriber(id, new byte[32]);
			fail("a subscriber lifted a ban");
		} catch (DbException expected) {
		}
	}

	private byte[] create(boolean publicChannel) throws Exception {
		return owner.manager.createChannel("name", "about", publicChannel)
				.getChannelId();
	}

	private ChannelTestNode join(byte[] id) throws Exception {
		return join(id, crypto.generateHybridSignatureKeyPair());
	}

	private ChannelTestNode join(byte[] id, KeyPair identity)
			throws Exception {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, tapped(),
				null, ChannelTestNode.identity(identity));
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

	private interface ReplyEdit {
		byte[] apply(byte[] reply) throws Exception;
	}

	@Nullable
	private volatile ReplyEdit replyEdit;

	private ChannelTransport tapped() {
		return new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] channelId,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				return network.bindServer(channelId, onionPrivateKey,
						handler);
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				try {
					Tap t = tap;
					byte[] sent = t == null ? request : t.onRequest(request);
					byte[] reply = network.requestFromOnion(onion, sent);
					ReplyEdit edit = replyEdit;
					boolean pull = ChannelConstants.WIRE_TYPE_PULL_REQUEST
							.equals(owner.pullCodec.peekType(sent));
					return pull && edit != null ? edit.apply(reply) : reply;
				} catch (IOException e) {
					throw e;
				} catch (Exception e) {
					throw new IOException(e);
				}
			}

			@Override
			public boolean isReachable(String onion) {
				return network.isReachable(onion);
			}
		};
	}

	private String sendChange(ChannelTestNode editor, byte[] id, long seq,
			String op, long base, String body, boolean on) throws Exception {
		KeyPair keys = memberKeys(editor, id);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		long ts = clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
		byte[] sig = owner.signatures.signUserPostChange(
				owner.codec.postChangeSignedInput(id, seq, op, base, on, body,
						ts), priv.getEd25519Component(),
				priv.getMlDsaPrivateKey());
		byte[] request = owner.pullCodec.encodeChangePostRequest(id, seq, op,
				base, on, body, ts, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), sig, null, null);
		return owner.pullCodec.decodeChangePostAck(owner.handle(id, request));
	}

	private byte[] commentRequest(byte[] id, ChannelTestNode reader,
			long seq, String body) throws Exception {
		KeyPair keys = memberKeys(reader, id);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		long ts = clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
		long commentId = random.nextLong();
		byte[] sig = owner.signatures.signUserComment(
				owner.codec.commentSignedInput(id, seq, commentId, body, "",
						ts), priv.getEd25519Component(),
				priv.getMlDsaPrivateKey());
		return owner.pullCodec.encodeCommentRequest(id, seq, commentId, body,
				"", ts, pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(),
				sig, null, null);
	}

	private KeyPair memberKeys(ChannelTestNode node, byte[] id)
			throws Exception {
		return (KeyPair) node.call("memberKeys",
				new Class<?>[] {byte[].class}, id);
	}

	private static int settingEvents(ChannelTestNode node, byte[] id) {
		int n = 0;
		synchronized (node.events) {
			for (Event e : node.events) {
				if (e instanceof ChannelStateChangedEvent
						&& ((ChannelStateChangedEvent) e).getKind()
						== ChannelStateChangedEvent.Kind.MANIFEST_UPDATED
						&& Arrays.equals(id,
						((ChannelStateChangedEvent) e).getChannelId())) {
					n++;
				}
			}
		}
		return n;
	}

	private static List<String> bodies(List<ChannelPost> posts) {
		List<String> out = new ArrayList<>();
		for (ChannelPost p : posts) out.add(p.getBody());
		return out;
	}

	private static BdfDictionary read(byte[] bytes) throws Exception {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(bytes));
		return r.readDictionary();
	}

	private static byte[] write(BdfDictionary d) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}

	private static byte[] withVersion(byte[] request, long version)
			throws Exception {
		BdfDictionary d = read(request);
		d.put("v", version);
		return write(d);
	}
}
