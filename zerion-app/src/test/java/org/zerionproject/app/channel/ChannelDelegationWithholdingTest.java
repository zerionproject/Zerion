package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * EXT-13-F06: revoking a delegation must not cut a subscriber off from the
 * rest of the channel. A post signed by a delegate whose certificate the
 * publisher has revoked stays in the chain and is withheld from view, on a
 * subscriber that held the certificate and on one that never saw it, as long
 * as a verified later post commits to it. Nothing that fails a signature or
 * chain check is admitted under that rule.
 */
public class ChannelDelegationWithholdingTest {

	private static final long HOUR = 3_600_000L;

	private final Random random = new Random(607);

	private CryptoComponent crypto;
	private ChannelCodec codec;
	private ChannelPullCodec pullCodec;
	private ChannelSignatures signatures;
	private ChannelChainVerifier chain;
	private ChannelPostValidator validator;
	private ChannelPullProtocol protocol;
	private ChannelCodecTestComponent bdf;
	private KeyPair publisher;
	private KeyPair delegate;
	private KeyPair stranger;
	private byte[] salt;
	private byte[] channelId;
	private ChannelDelegationCert cert;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		bdf = DaggerChannelCodecTestComponent.create();
		codec = new ChannelCodec(crypto);
		pullCodec = new ChannelPullCodec(bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		signatures = new ChannelSignatures(crypto);
		chain = new ChannelChainVerifier(codec);
		validator = new ChannelPostValidator(codec, signatures, chain);
		protocol = new ChannelPullProtocol(codec, pullCodec,
				new ChannelHmacChallenge(crypto),
				new ChannelContentKey(crypto), validator, signatures,
				crypto);
		publisher = crypto.generateHybridSignatureKeyPair();
		delegate = crypto.generateHybridSignatureKeyPair();
		stranger = crypto.generateHybridSignatureKeyPair();
		salt = new byte[32];
		random.nextBytes(salt);
		channelId = crypto.hash("org.zerionproject/CHANNEL_ID",
				publisher.getPublic().getEncoded(), salt);
		cert = cert(publisher, delegate, HOUR, 100 * HOUR, 1L);
	}

	@Test
	public void aRevokedCertificateIsReportedOnlyForAnAuthenticPost()
			throws Exception {
		ChannelState revoked = state(1L, Collections.singletonList(cert),
				Collections.singletonList(1L), noCerts());
		ChannelPost genuine = signed(delegate, 0, zero(), "x", delegate);
		assertEquals(ChannelPostValidator.Result.DELEGATION_REVOKED,
				validator.validate(revoked, genuine, null));
		ChannelPost forged = signed(stranger, 0, zero(), "x", delegate);
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(revoked, forged, null));
		ChannelDelegationCert selfIssued = cert(stranger, delegate, HOUR,
				100 * HOUR, 1L);
		ChannelState forgedCert = state(1L,
				Collections.singletonList(selfIssued),
				Collections.singletonList(1L), noCerts());
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(forgedCert, genuine, null));
		ChannelDelegationCert expired = cert(publisher, delegate, 0L, HOUR,
				1L);
		ChannelState outOfWindow = state(1L,
				Collections.singletonList(expired),
				Collections.singletonList(1L), noCerts());
		assertEquals(ChannelPostValidator.Result.DELEGATION_OUT_OF_WINDOW,
				validator.validate(outOfWindow, genuine, null));
	}

	@Test
	public void aRetiredCertificateStillVerifiesTheDelegatesPosts()
			throws Exception {
		ChannelPost genuine = signed(delegate, 0, zero(), "x", delegate);
		ChannelState retiredRevoked = state(1L, noCerts(),
				Collections.singletonList(1L),
				Collections.singletonList(cert));
		assertEquals(ChannelPostValidator.Result.DELEGATION_REVOKED,
				validator.validate(retiredRevoked, genuine, null));
		ChannelState retiredOnly = state(1L, noCerts(),
				Collections.<Long>emptyList(),
				Collections.singletonList(cert));
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(retiredOnly, genuine, null));
		ChannelState unknown = state(1L, noCerts(),
				Collections.singletonList(1L), noCerts());
		assertEquals(ChannelPostValidator.Result.DELEGATION_NOT_FOUND,
				validator.validate(unknown, genuine, null));
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validateChain(genuine, null));
		ChannelDelegationCert older = cert(publisher, delegate, HOUR,
				2 * HOUR, 0L);
		ChannelState twoRetired = state(1L, noCerts(),
				Collections.<Long>emptyList(),
				Arrays.asList(older, cert));
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(twoRetired, genuine, null));
	}

	@Test
	public void aPostIsJudgedUnderTheCertificateInForceWhenItWasMade()
			throws Exception {
		ChannelPost early = signed(delegate, 0, zero(), "x", delegate);
		long ts = early.getTimestampHourMs();
		ChannelDelegationCert first = cert(publisher, delegate, ts - HOUR,
				ts + HOUR, 1L);
		ChannelDelegationCert later = cert(publisher, delegate, ts + 2 * HOUR,
				ts + 9 * HOUR, 2L);
		ChannelState renewed = state(1L, Collections.singletonList(later),
				Collections.<Long>emptyList(),
				Collections.singletonList(first));
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(renewed, early, null));
		ChannelDelegationCert overlapping = cert(publisher, delegate,
				ts - HOUR, ts + 9 * HOUR, 2L);
		ChannelState revokedThenReissued = state(1L,
				Collections.singletonList(overlapping),
				Collections.singletonList(1L),
				Collections.singletonList(first));
		assertEquals(ChannelPostValidator.Result.DELEGATION_REVOKED,
				validator.validate(revokedThenReissued, early, null));
		ChannelState reissuedThenRevoked = state(1L, noCerts(),
				Collections.singletonList(2L),
				Arrays.asList(first, overlapping));
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(reissuedThenRevoked, early, null));
		ChannelState noneCovers = state(1L, Collections.singletonList(later),
				Collections.<Long>emptyList(), noCerts());
		assertEquals(ChannelPostValidator.Result.DELEGATION_OUT_OF_WINDOW,
				validator.validate(noneCovers, early, null));
	}

	@Test
	public void aSubscriberHoldingTheCertificateKeepsTheChainAcrossARevocation()
			throws Exception {
		List<ChannelPost> posts = new ArrayList<>();
		posts.add(signed(publisher, 0, zero(), "p0", null));
		posts.add(signed(delegate, 1, chain.hashOf(posts.get(0)), "d1",
				delegate));
		posts.add(signed(delegate, 2, chain.hashOf(posts.get(1)), "d2",
				delegate));
		posts.add(signed(publisher, 3, chain.hashOf(posts.get(2)), "p3",
				null));
		ChannelState local = state(1L, Collections.singletonList(cert),
				Collections.<Long>emptyList(), noCerts());
		ChannelPullProtocol.ProcessResult r = protocol
				.processSubscriberResponse(response(manifest(2L, noCerts(),
						Collections.singletonList(1L)), posts), local,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(r.error, r.ok);
		assertEquals(4, r.acceptedPosts.size());
		assertFalse(r.acceptedPosts.get(0).isWithheld());
		assertTrue(r.acceptedPosts.get(1).isWithheld());
		assertTrue(r.acceptedPosts.get(2).isWithheld());
		assertFalse(r.acceptedPosts.get(3).isWithheld());
		assertEquals("d1", r.acceptedPosts.get(1).getBody());
		assertArrayEquals(posts.get(1).getSignature(),
				r.acceptedPosts.get(1).getSignature());
		assertTrue(r.mergedState.getActiveDelegations().isEmpty());
		assertEquals(1, r.mergedState.getRetiredDelegations().size());
		assertEquals(1L, r.mergedState.getRetiredDelegations().get(0)
				.getDelegationSeq());
		assertTrue(r.mergedState.getRevokedDelegationSeqs().contains(1L));
	}

	@Test
	public void aSubscriberThatNeverSawTheCertificateHoldsUntilAVerifiedPostCommits()
			throws Exception {
		List<ChannelPost> posts = new ArrayList<>();
		posts.add(signed(publisher, 0, zero(), "p0", null));
		posts.add(signed(delegate, 1, chain.hashOf(posts.get(0)), "d1",
				delegate));
		posts.add(signed(delegate, 2, chain.hashOf(posts.get(1)), "d2",
				delegate));
		ChannelState local = state(1L, noCerts(),
				Collections.<Long>emptyList(), noCerts());
		BdfDictionary m = manifest(2L, noCerts(),
				Collections.singletonList(1L));
		ChannelPullProtocol.ProcessResult tail = protocol
				.processSubscriberResponse(response(m, posts), local,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(tail.error, tail.ok);
		assertEquals(1, tail.acceptedPosts.size());
		assertEquals(0L, tail.acceptedPosts.get(0).getSeqNum());
		assertTrue(tail.mergedState.getRetiredDelegations().isEmpty());

		posts.add(signed(publisher, 3, chain.hashOf(posts.get(2)), "p3",
				null));
		ChannelPullProtocol.ProcessResult committed = protocol
				.processSubscriberResponse(response(manifest(3L, noCerts(),
						Collections.singletonList(1L)), posts),
						tail.mergedState, tail.acceptedPosts, null);
		assertTrue(committed.error, committed.ok);
		assertEquals(3, committed.acceptedPosts.size());
		assertEquals(1L, committed.acceptedPosts.get(0).getSeqNum());
		assertTrue(committed.acceptedPosts.get(0).isWithheld());
		assertTrue(committed.acceptedPosts.get(1).isWithheld());
		assertEquals(3L, committed.acceptedPosts.get(2).getSeqNum());
		assertFalse(committed.acceptedPosts.get(2).isWithheld());
	}

	@Test
	public void nothingUnverifiableIsAdmittedUnderTheWithholdingRule()
			throws Exception {
		ChannelPost p0 = signed(publisher, 0, zero(), "p0", null);
		ChannelState holding = state(1L, Collections.singletonList(cert),
				Collections.<Long>emptyList(), noCerts());
		BdfDictionary revoking = manifest(2L, noCerts(),
				Collections.singletonList(1L));

		ChannelPost impostor = signed(stranger, 1, chain.hashOf(p0), "x",
				delegate);
		ChannelPost after = signed(publisher, 2, chain.hashOf(impostor),
				"p2", null);
		ChannelPullProtocol.ProcessResult forgedSig = protocol
				.processSubscriberResponse(response(revoking,
						Arrays.asList(p0, impostor, after)), holding,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(forgedSig.error, forgedSig.ok);
		assertEquals(1, forgedSig.acceptedPosts.size());

		byte[] wrongPrev = chain.hashOf(p0);
		wrongPrev[5] ^= 1;
		ChannelPost broken = signed(delegate, 1, wrongPrev, "x", delegate);
		ChannelPost afterBroken = signed(publisher, 2, chain.hashOf(broken),
				"p2", null);
		ChannelState unaware = state(1L, noCerts(),
				Collections.<Long>emptyList(), noCerts());
		ChannelPullProtocol.ProcessResult brokenChain = protocol
				.processSubscriberResponse(response(revoking,
						Arrays.asList(p0, broken, afterBroken)), unaware,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(brokenChain.error, brokenChain.ok);
		assertEquals(1, brokenChain.acceptedPosts.size());

		ChannelPost unknownDelegate = signed(stranger, 1, chain.hashOf(p0),
				"x", stranger);
		ChannelPost strangerNext = signed(stranger, 2,
				chain.hashOf(unknownDelegate), "y", stranger);
		ChannelPullProtocol.ProcessResult neverCommitted = protocol
				.processSubscriberResponse(response(revoking,
						Arrays.asList(p0, unknownDelegate, strangerNext)),
						unaware, Collections.<ChannelPost>emptyList(), null);
		assertTrue(neverCommitted.error, neverCommitted.ok);
		assertEquals(1, neverCommitted.acceptedPosts.size());

		ChannelPost publisherAsDelegate = signed(publisher, 1,
				chain.hashOf(p0), "x", delegate);
		ChannelPullProtocol.ProcessResult wrongSigner = protocol
				.processSubscriberResponse(response(revoking,
						Arrays.asList(p0, publisherAsDelegate)), holding,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(wrongSigner.error, wrongSigner.ok);
		assertEquals(1, wrongSigner.acceptedPosts.size());
	}

	@Test
	public void anActiveDelegateIsShownAndAReplacedOneIsRetired()
			throws Exception {
		ChannelPost p0 = signed(publisher, 0, zero(), "p0", null);
		ChannelPost d1 = signed(delegate, 1, chain.hashOf(p0), "d1",
				delegate);
		ChannelState local = state(1L, Collections.singletonList(cert),
				Collections.<Long>emptyList(), noCerts());
		ChannelPullProtocol.ProcessResult shown = protocol
				.processSubscriberResponse(response(manifest(2L,
						Collections.singletonList(cert),
						Collections.<Long>emptyList()),
						Arrays.asList(p0, d1)), local,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(shown.error, shown.ok);
		assertEquals(2, shown.acceptedPosts.size());
		assertFalse(shown.acceptedPosts.get(1).isWithheld());
		assertTrue(shown.mergedState.getRetiredDelegations().isEmpty());

		ChannelDelegationCert renewed = cert(publisher, delegate, HOUR,
				200 * HOUR, 2L);
		ChannelPullProtocol.ProcessResult replaced = protocol
				.processSubscriberResponse(response(manifest(3L,
						Collections.singletonList(renewed),
						Collections.<Long>emptyList()),
						Arrays.asList(p0, d1)), shown.mergedState,
						shown.acceptedPosts, null);
		assertTrue(replaced.error, replaced.ok);
		assertEquals(1, replaced.mergedState.getActiveDelegations().size());
		assertEquals(2L, replaced.mergedState.getActiveDelegations().get(0)
				.getDelegationSeq());
		assertEquals(1, replaced.mergedState.getRetiredDelegations().size());
		assertEquals(1L, replaced.mergedState.getRetiredDelegations().get(0)
				.getDelegationSeq());
	}

	@Test
	public void theRetiredListIsDeduplicatedAndBounded() throws Exception {
		List<ChannelDelegationCert> many = new ArrayList<>();
		for (long seq = 0; seq < ChannelState.MAX_RETIRED_DELEGATIONS + 10;
				seq++) {
			many.add(cert(publisher, delegate, HOUR, 2 * HOUR, seq));
		}
		List<ChannelDelegationCert> retired = ChannelState.retire(noCerts(),
				many);
		assertEquals(ChannelState.MAX_RETIRED_DELEGATIONS, retired.size());
		assertEquals(10L, retired.get(0).getDelegationSeq());
		List<ChannelDelegationCert> again = ChannelState.retire(retired,
				Collections.singletonList(many.get(20)));
		assertEquals(retired.size(), again.size());
		List<ChannelDelegationCert> plusOne = ChannelState.retire(
				Collections.singletonList(many.get(3)),
				Collections.singletonList(many.get(5)));
		assertEquals(2, plusOne.size());
		assertEquals(3L, plusOne.get(0).getDelegationSeq());
		assertEquals(5L, plusOne.get(1).getDelegationSeq());
	}

	@Test
	public void withholdingStoredPostsKeepsBytesAndRecountsUnread()
			throws Exception {
		ChannelPost p0 = signed(publisher, 0, zero(), "p0", null);
		ChannelPost d1 = signed(delegate, 1, chain.hashOf(p0), "d1",
				delegate);
		ChannelPost p2 = signed(publisher, 2, chain.hashOf(d1), "p2", null);
		ChannelPost d3 = signed(delegate, 3, chain.hashOf(p2), "d3",
				delegate);
		List<ChannelPost> posts = Arrays.asList(p0, d1, p2, d3);
		ChannelState stillActive = state(2L, Collections.singletonList(cert),
				Collections.<Long>emptyList(), noCerts());
		assertNull(ChannelWithholding.withholdRevoked(validator, stillActive,
				posts));
		ChannelState otherRevoked = state(2L,
				Collections.singletonList(cert),
				Collections.singletonList(9L), noCerts());
		assertNull(ChannelWithholding.withholdRevoked(validator, otherRevoked,
				posts));
		ChannelState revoked = state(2L, noCerts(),
				Collections.singletonList(1L),
				Collections.singletonList(cert));
		List<ChannelPost> out = ChannelWithholding.withholdRevoked(validator,
				revoked, posts);
		assertEquals(4, out.size());
		assertFalse(out.get(0).isWithheld());
		assertTrue(out.get(1).isWithheld());
		assertFalse(out.get(2).isWithheld());
		assertTrue(out.get(3).isWithheld());
		assertArrayEquals(chain.hashOf(d1), chain.hashOf(out.get(1)));
		assertArrayEquals(chain.hashOf(d3), chain.hashOf(out.get(3)));
		assertEquals(ChannelPostValidator.Result.OK, validator.validateChain(
				out.get(2), out.get(1)));
		assertEquals(2, ChannelWithholding.unreadCount(out));
		assertNull(ChannelWithholding.withholdRevoked(validator, revoked,
				out));
	}

	@Test
	public void theStoreRoundTripsWithheldPostsAndRetiredCertificates()
			throws Exception {
		ChannelStore store = new ChannelStore(new MemorySettings(),
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		ChannelState s = state(4L, noCerts(), Collections.singletonList(1L),
				Collections.singletonList(cert));
		store.putChannel(s);
		ChannelState back = store.getChannel(channelId);
		assertEquals(1, back.getRetiredDelegations().size());
		ChannelDelegationCert c = back.getRetiredDelegations().get(0);
		assertEquals(cert.getDelegationSeq(), c.getDelegationSeq());
		assertArrayEquals(cert.getDelegateeEd25519PubKey(),
				c.getDelegateeEd25519PubKey());
		assertArrayEquals(cert.getDelegateeMlDsaPubKey(),
				c.getDelegateeMlDsaPubKey());
		assertEquals(cert.getValidFromHourMs(), c.getValidFromHourMs());
		assertEquals(cert.getValidUntilHourMs(), c.getValidUntilHourMs());
		assertArrayEquals(cert.getSignature(), c.getSignature());

		ChannelPost p0 = signed(publisher, 0, zero(), "p0", null);
		ChannelPost d1 = signed(delegate, 1, chain.hashOf(p0), "d1",
				delegate).withheld();
		store.writePosts(channelId, Arrays.asList(p0, d1));
		List<ChannelPost> posts = store.getPosts(channelId);
		assertEquals(2, posts.size());
		assertFalse(posts.get(0).isWithheld());
		assertTrue(posts.get(1).isWithheld());
		assertEquals("d1", posts.get(1).getBody());
		assertArrayEquals(d1.getSignature(), posts.get(1).getSignature());
		assertArrayEquals(d1.getDelegateSignerEd25519PubKey(),
				posts.get(1).getDelegateSignerEd25519PubKey());
		assertArrayEquals(chain.hashOf(d1), chain.hashOf(posts.get(1)));
	}

	private static final class MemorySettings implements SettingsManager {
		private final Map<String, Settings> byNamespace = new HashMap<>();

		@Override
		public Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) s.putAll(stored);
			return s;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public void mergeSettings(Settings s, String namespace) {
			Settings merged = getSettings(namespace);
			merged.putAll(s);
			byNamespace.put(namespace, merged);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) throws DbException {
			mergeSettings(s, namespace);
		}
	}

	private static List<ChannelDelegationCert> noCerts() {
		return Collections.<ChannelDelegationCert>emptyList();
	}

	private ChannelState state(long manifestSeq,
			List<ChannelDelegationCert> active, List<Long> revoked,
			List<ChannelDelegationCert> retired) {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) publisher.getPublic();
		return new ChannelState(channelId, salt, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), "name", "description", null, HOUR,
				true, null, "", manifestSeq, false, -1L, null, null,
				active, revoked, 10L, null, ChannelState.NO_PINNED_POST,
				false, retired);
	}

	private BdfDictionary manifest(long manifestSeq,
			List<ChannelDelegationCert> active, List<Long> revoked)
			throws Exception {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) publisher.getPublic();
		byte[] input = codec.manifestSignedInput(channelId, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "name",
				"description", null, HOUR, true, null, "", manifestSeq,
				null, active, revoked, ChannelState.NO_PINNED_POST, false,
				true);
		byte[] sig = signatures.signManifest(input,
				(HybridSignaturePrivateKey) publisher.getPrivate());
		return pullCodec.encodeManifest(channelId, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "name",
				"description", null, HOUR, true, null, "", manifestSeq,
				null, active, revoked, ChannelState.NO_PINNED_POST, false,
				true, sig);
	}

	private byte[] response(BdfDictionary manifest, List<ChannelPost> posts)
			throws Exception {
		return pullCodec.encodePullResponse(manifest, posts, null,
				Collections.<String>emptyList(),
				Collections.<ChannelReaction>emptyList(),
				Collections.<ChannelComment>emptyList());
	}

	private ChannelDelegationCert cert(KeyPair issuer, KeyPair delegatee,
			long from, long until, long seq) throws GeneralSecurityException {
		HybridSignaturePublicKey dPub =
				(HybridSignaturePublicKey) delegatee.getPublic();
		byte[] input = codec.delegationSignedInput(channelId,
				dPub.getEd25519PublicKey(), dPub.getMlDsaPublicKey(), from,
				until, seq);
		byte[] sig = signatures.signDelegation(input,
				(HybridSignaturePrivateKey) issuer.getPrivate());
		return new ChannelDelegationCert(channelId, dPub.getEd25519PublicKey(),
				dPub.getMlDsaPublicKey(), from, until, seq, sig);
	}

	private ChannelPost signed(KeyPair signer, long seq, byte[] prev,
			String body, @Nullable KeyPair claimedDelegate)
			throws GeneralSecurityException {
		long ts = HOUR * (seq + 2);
		List<ChannelPost.ChannelAttachment> none =
				Collections.<ChannelPost.ChannelAttachment>emptyList();
		byte[] input = codec.postSignedInput(channelId, seq, prev, ts, body,
				codec.attachmentsHash(none), 0L);
		byte[] sig = signatures.signPost(input,
				(HybridSignaturePrivateKey) signer.getPrivate());
		if (claimedDelegate == null) {
			return new ChannelPost(channelId, seq, prev, ts, body, none, 0L,
					sig, false);
		}
		HybridSignaturePublicKey dPub =
				(HybridSignaturePublicKey) claimedDelegate.getPublic();
		return new ChannelPost(channelId, seq, prev, ts, body, none, 0L, sig,
				false, dPub.getEd25519PublicKey(), dPub.getMlDsaPublicKey());
	}

	private static byte[] zero() {
		return new byte[ChannelConstants.PREV_HASH_BYTES];
	}
}
