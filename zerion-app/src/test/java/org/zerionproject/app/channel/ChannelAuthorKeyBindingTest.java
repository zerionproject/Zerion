package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelApplication;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.IdentityManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelAuthorKeyBindingTest {

	private final Random random = new Random(4441);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode.TestChannel channel;
	private ChannelTestNode publisher;
	private ChannelTestNode member;
	private KeyPair identity;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		identity = crypto.generateHybridSignatureKeyPair();
	}

	@After
	public void tearDown() {
		if (publisher != null) publisher.deleteFiles();
		if (member != null) member.deleteFiles();
	}

	@Test
	public void aCommentUnderAKnownKeyWithAnotherMlDsaKeyIsRefused()
			throws Exception {
		openChannel(false);
		member.manager.postComment(channel.channelId, 0L, "genuine");
		KeyPair victim = memberKey();
		KeyPair forged = forge(victim);

		assertFalse("the forged comment is refused", send(comment(forged,
				0L, random.nextLong(), "forged", "")));
		List<ChannelComment> held =
				publisher.commentStore.getComments(channel.channelId);
		assertEquals(1, held.size());
		assertEquals("genuine", held.get(0).getBody());
		assertArrayEquals(ml(victim), held.get(0).getAuthorMlDsaPubKey());
		assertTrue("the author still comments", send(comment(victim, 1L,
				random.nextLong(), "again", "")));
	}

	@Test
	public void aForgedReactionCannotReplaceTheAuthorsReaction()
			throws Exception {
		openChannel(false);
		member.manager.reactToPost(channel.channelId, 0L, "+");
		KeyPair forged = forge(memberKey());
		clock.advance(ChannelTestNode.HOUR);

		assertFalse(send(reaction(forged, 0L, "-")));
		List<ChannelReaction> held =
				publisher.reactionStore.getReactions(channel.channelId);
		assertEquals(1, held.size());
		assertEquals("+", held.get(0).getEmoji());
		assertArrayEquals(ml(memberKey()), held.get(0).getSignerMlDsaPubKey());
	}

	@Test
	public void theChannelKeyIsBoundToTheChannelsMlDsaKey() throws Exception {
		openChannel(false);
		KeyPair forged = forge(channel.keys);

		assertFalse("no prior comment is needed", send(comment(forged, 0L,
				random.nextLong(), "as the owner", "")));
		assertFalse(send(reaction(forged, 0L, "+")));
		assertTrue(publisher.commentStore.getComments(channel.channelId)
				.isEmpty());
		assertTrue(publisher.reactionStore.getReactions(channel.channelId)
				.isEmpty());
		publisher.manager.postComment(channel.channelId, 0L, "the owner");
		assertEquals(1, publisher.commentStore.getComments(channel.channelId)
				.size());
	}

	@Test
	public void anEditorKeyIsBoundByItsCertificate() throws Exception {
		openChannel(false);
		KeyPair editor = crypto.generateHybridSignatureKeyPair();
		publisher.manager.delegatePublisher(channel.channelId, ed(editor),
				ml(editor), 0L);
		KeyPair forged = forge(editor);

		assertFalse("before the editor ever commented", send(comment(forged,
				0L, random.nextLong(), "as the editor", "")));
		assertTrue(send(comment(editor, 0L, random.nextLong(), "the editor",
				"")));
	}

	@Test
	public void aBoundKeyCannotBeCertifiedWithAnotherMlDsaKey()
			throws Exception {
		openChannel(false);
		member.manager.postComment(channel.channelId, 0L, "hello");
		KeyPair victim = memberKey();
		KeyPair forged = forge(victim);

		try {
			publisher.manager.delegatePublisher(channel.channelId,
					ed(forged), ml(forged), 0L);
			fail("a certificate for the other key was issued");
		} catch (DbException expected) {
		}
		assertTrue(publisher.store.getChannel(channel.channelId)
				.getActiveDelegations().isEmpty());
		publisher.manager.delegatePublisher(channel.channelId, ed(victim),
				ml(victim), 0L);
		assertEquals(1, publisher.store.getChannel(channel.channelId)
				.getActiveDelegations().size());
	}

	@Test
	public void aForgedAnnounceCannotRenameAMember() throws Exception {
		openChannel(false);
		member.manager.announceMyself(channel.channelId, "Alice");
		KeyPair victim = memberKey();
		KeyPair forged = forge(victim);
		clock.advance(ChannelTestNode.HOUR);

		assertFalse(send(announce(forged, "Mallory")));
		List<ChannelSubscriber> subs =
				publisher.subscriberStore.getSubscribers(channel.channelId);
		assertEquals(1, subs.size());
		assertEquals("Alice", subs.get(0).getDisplayName());
		assertArrayEquals(ml(victim), subs.get(0).getMlDsaPubKey());
		assertTrue("the member renames itself", send(announce(victim,
				"Alicia")));
	}

	@Test
	public void aForgedApplicationCannotTakeAnApprovedMembersPlace()
			throws Exception {
		openChannel(true);
		member.manager.applyToJoin(channel.channelId, "Applicant");
		KeyPair victim = memberKey();
		publisher.manager.approveApplication(channel.channelId, ed(victim));
		ChannelApplication approved = application();
		KeyPair forged = forge(victim);
		clock.advance(ChannelTestNode.HOUR);

		KeyPair eph = crypto.generateHybridAgreementKeyPair();
		assertFalse(send(apply(forged, "Applicant",
				eph.getPublic().getEncoded())));
		ChannelApplication held = application();
		assertEquals(ChannelApplication.Status.APPROVED, held.getStatus());
		assertArrayEquals(approved.getApplicantEphemeralAgreementPub(),
				held.getApplicantEphemeralAgreementPub());

		BdfDictionary denied = decode(publisher.handle(channel.channelId,
				checkApproval(forged)));
		assertEquals("DENIED", denied.getString("status"));
		assertNull(denied.getOptionalRaw("envelope"));
		BdfDictionary ok = decode(publisher.handle(channel.channelId,
				checkApproval(victim)));
		assertEquals("APPROVED", ok.getString("status"));
		assertNotNull(ok.getOptionalRaw("envelope"));
	}

	@Test
	public void recordsHeldFromBeforeTheUpdateBindTheirAuthors()
			throws Exception {
		openChannel(false);
		KeyPair commenter = crypto.generateHybridSignatureKeyPair();
		KeyPair reactor = crypto.generateHybridSignatureKeyPair();
		KeyPair announcer = crypto.generateHybridSignatureKeyPair();
		publisher.commentStore.putComment(channel.channelId,
				commentItem(commenter, 0L, "from 3.0.17"));
		publisher.reactionStore.putReaction(channel.channelId,
				publisher.reaction(channel, reactor, 0L, "+"));
		publisher.subscriberStore.putSubscriber(channel.channelId,
				new ChannelSubscriber("Old", ed(announcer), ml(announcer),
						clock.currentTimeMillis(), false));
		assertTrue("no pin exists yet", publisher.settings.getSettings(
				ChannelAuthorPins.namespace(channel.channelId)).isEmpty());

		assertFalse(send(comment(forge(commenter), 1L, random.nextLong(),
				"x", "")));
		assertFalse(send(reaction(forge(reactor), 1L, "x")));
		assertFalse(send(announce(forge(announcer), "Mallory")));
		assertTrue(send(comment(commenter, 1L, random.nextLong(), "y", "")));
		assertTrue(send(reaction(reactor, 1L, "y")));
	}

	@Test
	public void aChangedMlDsaKeyIsRefusedLikeAnyOtherAndANewKeyIsANewAuthor()
			throws Exception {
		openChannel(false);
		member.manager.reactToPost(channel.channelId, 0L, "+");
		KeyPair victim = memberKey();
		KeyPair rotated = forge(victim);
		clock.advance(ChannelTestNode.HOUR);

		assertFalse("a key change signed by both new halves",
				send(reaction(rotated, 1L, "+")));
		assertFalse(send(comment(rotated, 1L, random.nextLong(), "new key",
				"")));
		KeyPair fresh = crypto.generateHybridSignatureKeyPair();
		assertTrue("a new member key is a new author",
				send(reaction(fresh, 1L, "+")));
		assertEquals(2, publisher.reactionStore
				.getReactions(channel.channelId).size());
	}

	@Test
	public void malformedKeysAreRefusedBeforeAnyStateIsWritten()
			throws Exception {
		openChannel(false);
		KeyPair author = crypto.generateHybridSignatureKeyPair();
		ChannelComment good = commentItem(author, 0L, "x");
		Set<String> before = rows(publisher);

		assertFalse(send(publisher.pullCodec.encodeCommentRequest(
				channel.channelId, 0L, good.getCommentId(), "x", "",
				good.getTimestampHourMs(), Arrays.copyOf(ed(author), 31),
				ml(author), good.getSignature(), null, null)));
		assertFalse(send(publisher.pullCodec.encodeCommentRequest(
				channel.channelId, 0L, good.getCommentId(), "x", "",
				good.getTimestampHourMs(), ed(author),
				Arrays.copyOf(ml(author), ml(author).length - 1),
				good.getSignature(), null, null)));
		assertFalse(send(publisher.pullCodec.encodeCommentRequest(
				channel.channelId, 0L, good.getCommentId(), "x", "",
				good.getTimestampHourMs(), ed(author), ml(author),
				Arrays.copyOf(good.getSignature(),
						PostQuantumConstants.HYBRID_SIGNATURE_BYTES - 1),
				null, null)));
		assertFalse(send(publisher.pullCodec.encodeReactionRequest(
				channel.channelId, 0L, "+", good.getTimestampHourMs(),
				ed(author), new byte[0], good.getSignature(), null, null)));
		assertEquals(before, rows(publisher));
	}

	@Test
	public void aReplayedCommentChangesNothingAndCannotCarryAnotherKey()
			throws Exception {
		openChannel(false);
		KeyPair author = crypto.generateHybridSignatureKeyPair();
		long id = random.nextLong();
		byte[] request = comment(author, 0L, id, "once", "");
		assertTrue(send(request));
		List<ChannelComment> after =
				publisher.commentStore.getComments(channel.channelId);
		assertTrue("an identical replay is acknowledged", send(request));
		assertTrue(ChannelCommentPolicy.sameSet(after,
				publisher.commentStore.getComments(channel.channelId)));
		assertFalse("the same id under another key", send(comment(
				forge(author), 0L, id, "once", "")));
		assertTrue(ChannelCommentPolicy.sameSet(after,
				publisher.commentStore.getComments(channel.channelId)));
	}

	@Test
	public void anOlderClientsRequestIsJudgedByTheSameBinding()
			throws Exception {
		openChannel(false);
		KeyPair author = crypto.generateHybridSignatureKeyPair();
		assertTrue(send(asVersion(comment(author, 0L, random.nextLong(),
				"from 3.0.17", ""), 2L)));
		assertFalse(send(asVersion(comment(forge(author), 0L,
				random.nextLong(), "forged", ""), 2L)));
		assertFalse(send(asVersion(comment(forge(author), 0L,
				random.nextLong(), "forged", ""), -1L)));
	}

	@Test
	public void leavingAChannelRemovesItsBindings() throws Exception {
		openChannel(false);
		member.manager.postComment(channel.channelId, 0L, "hello");
		member.manager.refreshChannel(channel.channelId);
		assertFalse(member.settings.getSettings(
				ChannelAuthorPins.namespace(channel.channelId)).isEmpty());
		member.manager.leaveChannel(channel.channelId);
		assertTrue(member.settings.getSettings(
				ChannelAuthorPins.namespace(channel.channelId)).isEmpty());
	}

	private void openChannel(boolean requiresApproval) throws Exception {
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport(), null,
				ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair()));
		byte[] capability = null;
		byte[] content = null;
		if (requiresApproval) {
			capability = new byte[32];
			random.nextBytes(capability);
			content = publisher.contentKey.generateContentKey();
		}
		publisher.seedPublisher(channel, !requiresApproval, capability,
				content, requiresApproval, publisher.posts(channel, 3));
		publisher.manager.setDiscussionsEnabled(channel.channelId, true);
		IdentityManager me = ChannelTestNode.identity(identity);
		member = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId), null,
				me);
		member.seedSubscriber(channel, !requiresApproval, null, null,
				requiresApproval ? java.util.Collections.<org.zerionproject
						.app.api.channel.ChannelPost>emptyList()
						: publisher.store.getPosts(channel.channelId));
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

	private boolean send(byte[] request) throws Exception {
		return ChannelTestNode.ackOk(publisher.handle(channel.channelId,
				request));
	}

	private long hour() {
		return clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
	}

	private ChannelComment commentItem(KeyPair signer, long seq, String body)
			throws Exception {
		long id = random.nextLong();
		long ts = hour();
		byte[] sig = publisher.signatures.signUserComment(
				publisher.codec.commentSignedInput(channel.channelId, seq, id,
						body, "", ts), edPriv(signer), mlPriv(signer));
		return new ChannelComment(seq, id, body, "", ed(signer), ml(signer),
				ts, sig);
	}

	private byte[] comment(KeyPair signer, long seq, long id, String body,
			String name) throws Exception {
		long ts = hour();
		byte[] sig = publisher.signatures.signUserComment(
				publisher.codec.commentSignedInput(channel.channelId, seq, id,
						body, name, ts), edPriv(signer), mlPriv(signer));
		return publisher.pullCodec.encodeCommentRequest(channel.channelId,
				seq, id, body, name, ts, ed(signer), ml(signer), sig, null,
				null);
	}

	private byte[] reaction(KeyPair signer, long seq, String emoji)
			throws Exception {
		long ts = hour();
		byte[] sig = publisher.signatures.signUserReaction(
				publisher.codec.reactionSignedInput(channel.channelId, seq,
						emoji, ts), edPriv(signer), mlPriv(signer));
		return publisher.pullCodec.encodeReactionRequest(channel.channelId,
				seq, emoji, ts, ed(signer), ml(signer), sig, null, null);
	}

	private byte[] announce(KeyPair signer, String name) throws Exception {
		long ts = hour();
		byte[] sig = publisher.signatures.signUserAnnounce(
				publisher.codec.announceSignedInput(channel.channelId, name,
						ts), edPriv(signer), mlPriv(signer));
		return publisher.pullCodec.encodeAnnounceRequest(channel.channelId,
				name, ts, ed(signer), ml(signer), sig, null, null);
	}

	private byte[] apply(KeyPair signer, String name, byte[] eph)
			throws Exception {
		long ts = hour();
		byte[] sig = publisher.signatures.signUserApplication(
				publisher.codec.applicationSignedInput(channel.channelId,
						name, ts, eph), edPriv(signer), mlPriv(signer));
		return publisher.pullCodec.encodeApplyRequest(channel.channelId,
				name, ts, ed(signer), ml(signer), eph, sig);
	}

	private byte[] checkApproval(KeyPair signer) throws Exception {
		long ts = hour();
		byte[] sig = publisher.signatures.signUserCheckApproval(
				publisher.codec.checkApprovalSignedInput(channel.channelId,
						ts), edPriv(signer), mlPriv(signer));
		return publisher.pullCodec.encodeCheckApprovalRequest(
				channel.channelId, ts, ed(signer), ml(signer), sig);
	}

	private byte[] asVersion(byte[] request, long version) throws Exception {
		BdfDictionary d = decode(request);
		if (version < 0L) d.remove("v");
		else d.put("v", version);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		org.zerionproject.core.api.data.BdfWriter w =
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory()
						.createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}

	private static Set<String> rows(ChannelTestNode node) {
		Set<String> out = new HashSet<>();
		for (String[] r : node.settings.rows()) {
			out.add(r[0] + "|" + r[1] + "|" + r[2]);
		}
		return out;
	}

	private ChannelApplication application() throws Exception {
		List<ChannelApplication> all =
				publisher.manager.listAllApplications(channel.channelId);
		assertEquals(1, all.size());
		return all.get(0);
	}

	private static BdfDictionary decode(byte[] bytes) throws Exception {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(bytes));
		return r.readDictionary();
	}

	private static byte[] ed(KeyPair k) {
		return ((HybridSignaturePublicKey) k.getPublic())
				.getEd25519PublicKey();
	}

	private static byte[] ml(KeyPair k) {
		return ((HybridSignaturePublicKey) k.getPublic()).getMlDsaPublicKey();
	}

	private static org.zerionproject.core.api.crypto.PrivateKey edPriv(
			KeyPair k) {
		return ((HybridSignaturePrivateKey) k.getPrivate())
				.getEd25519Component();
	}

	private static byte[] mlPriv(KeyPair k) {
		return ((HybridSignaturePrivateKey) k.getPrivate())
				.getMlDsaPrivateKey();
	}
}
