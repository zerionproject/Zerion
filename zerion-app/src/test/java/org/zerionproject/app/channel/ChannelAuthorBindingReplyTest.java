package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelAuthorBindingReplyTest {

	private final Random random = new Random(9817);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode.TestChannel channel;
	private ChannelTestNode publisher;
	private ChannelTestNode member;
	private KeyPair identity;
	@Nullable
	private volatile Long requestVersion;
	@Nullable
	private volatile ReplyEdit replyEdit;

	private interface ReplyEdit {
		byte[] apply(byte[] reply) throws Exception;
	}

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		identity = crypto.generateHybridSignatureKeyPair();
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport(), null,
				ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair()));
		publisher.seedPublisher(channel, true, null, null, false,
				publisher.posts(channel, 3));
		publisher.manager.setDiscussionsEnabled(channel.channelId, true);
		member = new ChannelTestNode(crypto, clock, network(), null,
				ChannelTestNode.identity(identity));
		member.seedSubscriber(channel, true, null, null,
				publisher.store.getPosts(channel.channelId));
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		member.deleteFiles();
	}

	@Test
	public void aVersion3ReplyIsSignedOverTheBindingsItCarries()
			throws Exception {
		KeyPair a = crypto.generateHybridSignatureKeyPair();
		assertTrue(submit(a, 0L, "from a"));
		requestVersion = 3L;
		byte[] reply = publisher.handle(channel.channelId, withVersion(
				publisher.pullCodec.encodePullRequest(channel.channelId, -1L,
						null, null), 3L));
		ChannelPullCodec.PullResponse r = publisher.pullCodec
				.decodePullResponse(reply, channel.channelId);
		assertEquals(ChannelConstants.PROTOCOL_VERSION_AUTHOR_BINDING,
				r.version);
		byte[] input = manifestInput(r.manifest);
		byte[] sig = r.manifest.getRaw("signature");
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) channel.keys.getPublic();
		byte[] digest = ChannelAuthorBinding.digest(crypto, r.reactions,
				r.comments);
		assertTrue(publisher.signatures.verifyBoundManifest(sig,
				ChannelCodec.boundManifestSignedInput(input, digest), pub));
		assertFalse("not valid as a version 2 manifest",
				publisher.signatures.verifyManifest(sig, input, pub));

		member.manager.refreshChannel(channel.channelId);
		assertEquals(1, member.commentStore.getComments(channel.channelId)
				.size());
		assertArrayEquals(ChannelAuthorBinding.keyHash(crypto, ml(a)),
				member.store.pins().get(channel.channelId, ed(a)));
		assertTrue(member.store.pins().boundReplies(channel.channelId));
	}

	@Test
	public void anItemInjectedIntoAVersion3ReplyFailsTheWholeReply()
			throws Exception {
		KeyPair a = crypto.generateHybridSignatureKeyPair();
		assertTrue(submit(a, 0L, "genuine"));
		ChannelComment forged = item(forge(a), 0L, "forged");
		replyEdit = reply -> withComment(reply, forged, true);
		assertPullFails();
		ChannelComment stranger = item(
				crypto.generateHybridSignatureKeyPair(), 0L, "stranger");
		replyEdit = reply -> withComment(reply, stranger, false);
		assertPullFails();
		assertTrue(member.commentStore.getComments(channel.channelId)
				.isEmpty());
		assertNull(member.store.pins().get(channel.channelId, ed(a)));

		replyEdit = null;
		member.manager.refreshChannel(channel.channelId);
		List<ChannelComment> held =
				member.commentStore.getComments(channel.channelId);
		assertEquals(1, held.size());
		assertEquals("genuine", held.get(0).getBody());
	}

	@Test
	public void afterAVersion3ReplyALowerVersionReplyBringsNoItems()
			throws Exception {
		KeyPair a = crypto.generateHybridSignatureKeyPair();
		KeyPair b = crypto.generateHybridSignatureKeyPair();
		assertTrue(submit(a, 0L, "first"));
		member.manager.refreshChannel(channel.channelId);
		assertEquals(1, member.commentStore.getComments(channel.channelId)
				.size());
		byte[] cursor = member.commentStore.sync().cursor(channel.channelId);

		requestVersion = 2L;
		assertTrue(submit(b, 1L, "second"));
		publisher.manager.publishPost(channel.channelId, "a new post", 0L);
		member.manager.refreshChannel(channel.channelId);
		assertEquals("posts still arrive", 4,
				member.store.getPosts(channel.channelId).size());
		assertEquals("comments do not", 1, member.commentStore
				.getComments(channel.channelId).size());
		assertArrayEquals(cursor,
				member.commentStore.sync().cursor(channel.channelId));

		requestVersion = null;
		member.manager.refreshChannel(channel.channelId);
		assertEquals(2, member.commentStore.getComments(channel.channelId)
				.size());
	}

	@Test
	public void aSubscriberKeepsItsBindingsAgainstAnOlderPublishersItems()
			throws Exception {
		requestVersion = 2L;
		KeyPair a = crypto.generateHybridSignatureKeyPair();
		KeyPair b = crypto.generateHybridSignatureKeyPair();
		assertTrue(submit(a, 0L, "genuine"));
		member.manager.refreshChannel(channel.channelId);
		assertFalse(member.store.pins().boundReplies(channel.channelId));

		publisher.commentStore.putComment(channel.channelId,
				item(forge(a), 1L, "accepted by an older publisher"));
		assertTrue(submit(b, 1L, "from b"));
		member.manager.refreshChannel(channel.channelId);

		List<String> bodies = new ArrayList<>();
		for (ChannelComment c
				: member.commentStore.getComments(channel.channelId)) {
			bodies.add(c.getBody());
			if (Arrays.equals(c.getAuthorEd25519PubKey(), ed(a))) {
				assertArrayEquals(ml(a), c.getAuthorMlDsaPubKey());
			}
		}
		assertEquals(Arrays.asList("genuine", "from b"), bodies);
	}

	@Test
	public void theOwnersSignatureDoesNotOverrideASubscribersBinding()
			throws Exception {
		KeyPair a = crypto.generateHybridSignatureKeyPair();
		assertTrue(submit(a, 0L, "genuine"));
		member.manager.refreshChannel(channel.channelId);
		publisher.commentStore.putComment(channel.channelId,
				item(forge(a), 1L, "countersigned forgery"));
		member.manager.refreshChannel(channel.channelId);
		List<ChannelComment> held =
				member.commentStore.getComments(channel.channelId);
		assertEquals(1, held.size());
		assertEquals("genuine", held.get(0).getBody());
	}

	@Test
	public void theMembersOwnKeyIsBoundBeforeAnyReply() throws Exception {
		requestVersion = 2L;
		member.manager.postComment(channel.channelId, 0L, "mine");
		KeyPair own = memberKey();
		assertArrayEquals(ChannelAuthorBinding.keyHash(crypto, ml(own)),
				member.store.pins().get(channel.channelId, ed(own)));
		publisher.commentStore.putComment(channel.channelId,
				item(forge(own), 1L, "not mine"));
		member.manager.refreshChannel(channel.channelId);
		List<ChannelComment> held =
				member.commentStore.getComments(channel.channelId);
		assertEquals(1, held.size());
		assertEquals("mine", held.get(0).getBody());
	}

	@Test
	public void olderSubscribersStillGetTheRepliesTheyUnderstand()
			throws Exception {
		KeyPair a = crypto.generateHybridSignatureKeyPair();
		assertTrue(submit(a, 0L, "hello"));
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) channel.keys.getPublic();

		byte[] v2 = publisher.handle(channel.channelId, withVersion(
				publisher.pullCodec.encodePullRequest(channel.channelId, -1L,
						null, null), 2L));
		ChannelPullCodec.PullResponse r2 = publisher.pullCodec
				.decodePullResponse(v2, channel.channelId);
		assertEquals(ChannelConstants.PROTOCOL_VERSION_DELTAS, r2.version);
		assertTrue(publisher.signatures.verifyManifest(
				r2.manifest.getRaw("signature"), manifestInput(r2.manifest),
				pub));
		assertEquals(1, r2.comments.size());

		byte[] legacy = publisher.handle(channel.channelId, withVersion(
				publisher.pullCodec.encodePullRequest(channel.channelId, -1L,
						null, null), -1L));
		ChannelPullCodec.PullResponse r1 = publisher.pullCodec
				.decodePullResponse(legacy, channel.channelId);
		assertEquals(1, r1.version);
		assertNull(r1.commentsSync);
		assertTrue(publisher.signatures.verifyManifest(
				r1.manifest.getRaw("signature"), manifestInput(r1.manifest),
				pub));
		assertEquals(1, r1.comments.size());
	}

	@Test
	public void aDecoderDropsEntriesWithMalformedKeys() throws Exception {
		KeyPair a = crypto.generateHybridSignatureKeyPair();
		assertTrue(submit(a, 0L, "fine"));
		byte[] reply = publisher.handle(channel.channelId,
				publisher.pullCodec.encodePullRequest(channel.channelId, -1L,
						null, null));
		ChannelComment shortKey = new ChannelComment(0L, 9L, "short", "",
				ed(a), Arrays.copyOf(ml(a), 100), clock.currentTimeMillis(),
				new byte[0]);
		ChannelPullCodec.PullResponse r = publisher.pullCodec
				.decodePullResponse(withComment(reply, shortKey, true),
						channel.channelId);
		assertEquals(1, r.comments.size());
		assertEquals("fine", r.comments.get(0).getBody());
	}

	private void assertPullFails() throws Exception {
		try {
			member.manager.refreshChannel(channel.channelId);
			fail("the altered reply was accepted");
		} catch (DbException expected) {
		}
	}

	private boolean submit(KeyPair signer, long seq, String body)
			throws Exception {
		ChannelComment c = item(signer, seq, body);
		return ChannelTestNode.ackOk(publisher.handle(channel.channelId,
				publisher.pullCodec.encodeCommentRequest(channel.channelId,
						seq, c.getCommentId(), body, "",
						c.getTimestampHourMs(), c.getAuthorEd25519PubKey(),
						c.getAuthorMlDsaPubKey(), c.getSignature(), null,
						null)));
	}

	private ChannelComment item(KeyPair signer, long seq, String body)
			throws Exception {
		long id = random.nextLong();
		long ts = clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		byte[] sig = publisher.signatures.signUserComment(
				publisher.codec.commentSignedInput(channel.channelId, seq, id,
						body, "", ts), priv.getEd25519Component(),
				priv.getMlDsaPrivateKey());
		return new ChannelComment(seq, id, body, "", ed(signer), ml(signer),
				ts, sig);
	}

	private KeyPair memberKey() {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) identity.getPrivate();
		return ChannelMemberKeys.derive(crypto,
				priv.getEd25519Component().getEncoded(), channel.channelId);
	}

	private KeyPair forge(KeyPair victim) {
		KeyPair attacker = crypto.generateHybridSignatureKeyPair();
		HybridSignaturePrivateKey victimPriv =
				(HybridSignaturePrivateKey) victim.getPrivate();
		HybridSignaturePrivateKey attackerPriv =
				(HybridSignaturePrivateKey) attacker.getPrivate();
		return new KeyPair(new HybridSignaturePublicKey(ed(victim),
				ml(attacker)), new HybridSignaturePrivateKey(
				victimPriv.getEd25519Component().getEncoded(),
				attackerPriv.getMlDsaPrivateKey()));
	}

	private byte[] manifestInput(BdfDictionary m) throws Exception {
		List<org.zerionproject.app.api.channel.ChannelDelegationCert> none =
				new ArrayList<>();
		return publisher.codec.manifestSignedInput(m.getRaw("channelId"),
				m.getRaw("salt"), m.getRaw("publisherEd25519"),
				m.getRaw("publisherMlDsa"), m.getString("name"),
				m.getString("description"), m.getOptionalRaw("avatarHash"),
				m.getLong("createdAtHourMs"), m.getBoolean("publicChannel"),
				m.getOptionalRaw("joinCapability"), m.getString("currentOnion"),
				m.getLong("manifestSeq"), m.getOptionalRaw("contentKeyHash"),
				none, new ArrayList<Long>(), m.getLong("pinnedPostSeq"),
				m.getBoolean("requiresApproval"), true);
	}

	private ChannelTransport network() {
		ChannelTransport inner =
				ChannelTestNode.servedBy(publisher, channel.channelId);
		return new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				throw new IOException();
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				boolean pull = ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(
						publisher.pullCodec.peekType(request));
				Long v = requestVersion;
				try {
					byte[] sent = pull && v != null
							? withVersion(request, v) : request;
					byte[] reply = inner.requestFromOnion(onion, sent);
					ReplyEdit edit = replyEdit;
					return pull && edit != null ? edit.apply(reply) : reply;
				} catch (IOException e) {
					throw e;
				} catch (Exception e) {
					throw new IOException(e);
				}
			}

			@Override
			public boolean isReachable(String onion) {
				return true;
			}
		};
	}

	private static byte[] withVersion(byte[] request, long version)
			throws Exception {
		BdfDictionary d = read(request);
		if (version < 0L) d.remove("v");
		else d.put("v", version);
		return write(d);
	}

	private static byte[] withComment(byte[] reply, ChannelComment c,
			boolean first) throws Exception {
		BdfDictionary d = read(reply);
		BdfDictionary cd = new BdfDictionary();
		cd.put("seq", c.getParentPostSeqNum());
		cd.put("id", c.getCommentId());
		cd.put("body", c.getBody());
		cd.put("name", c.getAuthorDisplayName());
		cd.put("ed", c.getAuthorEd25519PubKey());
		cd.put("ml", c.getAuthorMlDsaPubKey());
		cd.put("ts", c.getTimestampHourMs());
		if (c.getSignature().length > 0) cd.put("sig", c.getSignature());
		BdfList next = new BdfList();
		if (first) next.add(cd);
		next.addAll(d.getList("comments"));
		if (!first) next.add(cd);
		d.put("comments", next);
		return write(d);
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

	private static byte[] ed(KeyPair k) {
		return ((HybridSignaturePublicKey) k.getPublic())
				.getEd25519PublicKey();
	}

	private static byte[] ml(KeyPair k) {
		return ((HybridSignaturePublicKey) k.getPublic()).getMlDsaPublicKey();
	}
}
