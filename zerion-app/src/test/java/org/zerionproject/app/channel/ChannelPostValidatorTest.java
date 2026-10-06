package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;

public class ChannelPostValidatorTest {

	private static final long HOUR = 3_600_000L;

	private final Random random = new Random(83);
	private final byte[] channelId = new byte[32];

	private CryptoComponent crypto;
	private ChannelCodec codec;
	private ChannelSignatures signatures;
	private ChannelChainVerifier chain;
	private ChannelPostValidator validator;
	private KeyPair publisher;
	private KeyPair delegate;
	private KeyPair stranger;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		codec = new ChannelCodec(crypto);
		signatures = new ChannelSignatures(crypto);
		chain = new ChannelChainVerifier(codec);
		validator = new ChannelPostValidator(codec, signatures, chain);
		random.nextBytes(channelId);
		publisher = crypto.generateHybridSignatureKeyPair();
		delegate = crypto.generateHybridSignatureKeyPair();
		stranger = crypto.generateHybridSignatureKeyPair();
	}

	@Test
	public void aSignedGenesisPostAndItsSuccessorAreAccepted()
			throws Exception {
		ChannelState state = state(Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList());
		ChannelPost genesis = signed(publisher, 0, zero(), "first", null);
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(state, genesis, null));
		ChannelPost second = signed(publisher, 1, chain.hashOf(genesis),
				"second", null);
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(state, second, genesis));
	}

	@Test
	public void sequenceAndChainMistakesAreRefusedBeforeSignatures()
			throws Exception {
		ChannelState state = state(Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList());
		ChannelPost genesis = signed(publisher, 0, zero(), "first", null);
		assertEquals(ChannelPostValidator.Result.SEQ_OUT_OF_ORDER,
				validator.validate(state,
						signed(publisher, 1, zero(), "x", null), null));
		byte[] notZero = zero();
		notZero[0] = 1;
		assertEquals(ChannelPostValidator.Result.CHAIN_BROKEN,
				validator.validate(state,
						signed(publisher, 0, notZero, "x", null), null));
		assertEquals(ChannelPostValidator.Result.SEQ_OUT_OF_ORDER,
				validator.validate(state,
						signed(publisher, 2, chain.hashOf(genesis), "x",
								null), genesis));
		assertEquals(ChannelPostValidator.Result.SEQ_OUT_OF_ORDER,
				validator.validate(state,
						signed(publisher, 0, chain.hashOf(genesis), "x",
								null), genesis));
		assertEquals(ChannelPostValidator.Result.CHAIN_BROKEN,
				validator.validate(state,
						signed(publisher, 1, zero(), "x", null), genesis));
		ChannelPost forged = signed(publisher, 1, chain.hashOf(genesis),
				"x", null);
		byte[] replayed = chain.hashOf(genesis);
		replayed[3] ^= 1;
		assertEquals(ChannelPostValidator.Result.CHAIN_BROKEN,
				validator.validate(state, new ChannelPost(channelId, 1,
						replayed, forged.getTimestampHourMs(), "x",
						forged.getAttachments(), 0L, forged.getSignature(),
						false), genesis));
	}

	@Test
	public void anOversizedBodyIsRefusedWhateverItsSignature()
			throws Exception {
		ChannelState state = state(Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList());
		StringBuilder big = new StringBuilder();
		for (int i = 0; i <= ChannelConstants.MAX_POST_BODY_CHARS; i++) {
			big.append('a');
		}
		assertEquals(ChannelPostValidator.Result.BODY_TOO_LARGE,
				validator.validate(state,
						signed(publisher, 0, zero(), big.toString(), null),
						null));
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(state,
						signed(publisher, 0, zero(),
								big.substring(1), null), null));
	}

	@Test
	public void aPostSignedByAnyoneButThePublisherIsRefused()
			throws Exception {
		ChannelState state = state(Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList());
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(state,
						signed(stranger, 0, zero(), "x", null), null));
		ChannelPost good = signed(publisher, 0, zero(), "x", null);
		ChannelPost[] edited = {
				new ChannelPost(channelId, 0, zero(),
						good.getTimestampHourMs(), "y",
						good.getAttachments(), 0L, good.getSignature(),
						false),
				new ChannelPost(channelId, 0, zero(),
						good.getTimestampHourMs() + HOUR, "x",
						good.getAttachments(), 0L, good.getSignature(),
						false),
				new ChannelPost(channelId, 0, zero(),
						good.getTimestampHourMs(), "x",
						good.getAttachments(), 1L, good.getSignature(),
						false),
				new ChannelPost(channelId, 0, zero(),
						good.getTimestampHourMs(), "x",
						good.getAttachments(), 0L,
						flipped(good.getSignature()), false),
				new ChannelPost(channelId, 0, zero(),
						good.getTimestampHourMs(), "x",
						good.getAttachments(), 0L, new byte[0], false),
		};
		for (ChannelPost p : edited) {
			assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
					validator.validate(state, p, null));
		}
	}

	@Test
	public void aPostAtTheNextNumberWithAnotherLinkIsAcceptedWhenGapsAre()
			throws Exception {
		ChannelState state = state(Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList());
		ChannelPost genesis = signed(publisher, 0, zero(), "first", null);
		ChannelChainTip tip = new ChannelChainTip(0L, chain.hashOf(genesis));
		byte[] other = zero();
		other[5] = 9;
		ChannelPost fork = signed(publisher, 1, other, "after a restore",
				null);
		assertEquals(ChannelPostValidator.Result.CHAIN_BROKEN,
				validator.validate(state, fork, tip, false));
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(state, fork, tip, true));
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(state, signed(stranger, 1, other, "x",
						null), tip, true));
	}

	@Test
	public void aNumberBeyondTheBoundIsRefusedEvenWhenGapsAre()
			throws Exception {
		ChannelState state = state(Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList());
		ChannelPost genesis = signed(publisher, 0, zero(), "first", null);
		ChannelChainTip tip = new ChannelChainTip(0L, chain.hashOf(genesis));
		long bound = ChannelTestNode.constant("MAX_SEQUENCE_NUMBER",
				Long.MAX_VALUE - 1L);
		ChannelPost far = signed(publisher, bound + 1L, zero(), "far",
				null);
		assertEquals(ChannelPostValidator.Result.SEQ_OUT_OF_ORDER,
				validator.validate(state, far, tip, true));
		assertEquals(ChannelPostValidator.Result.SEQ_OUT_OF_ORDER,
				validator.validate(state, far, null, true));
		ChannelPost atBound = signed(publisher, bound, zero(), "edge",
				null);
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(state, atBound, tip, true));
	}

	@Test
	public void aKeyGrantedAgainAfterARevocationIsJudgedUnderTheNewGrant()
			throws Exception {
		ChannelDelegationCert revoked = cert(publisher, delegate, HOUR, 0L,
				1L);
		ChannelDelegationCert granted = cert(publisher, delegate, 5 * HOUR,
				0L, 2L);
		ChannelState state = state(java.util.Arrays.asList(revoked, granted),
				Collections.singletonList(1L));
		ChannelPost before = signed(delegate, 1, zero(), "before", delegate);
		assertEquals(3 * HOUR, before.getTimestampHourMs());
		assertEquals(ChannelPostValidator.Result.DELEGATION_REVOKED,
				validator.validateSigner(state, before));
		ChannelPost after = signed(delegate, 4, zero(), "after", delegate);
		assertEquals(6 * HOUR, after.getTimestampHourMs());
		assertEquals("a key granted again stays refused until the revoked "
				+ "window ends", ChannelPostValidator.Result.OK,
				validator.validateSigner(state, after));
	}

	@Test
	public void aDelegateWithAValidCertificateIsAccepted() throws Exception {
		ChannelDelegationCert cert = cert(publisher, delegate, HOUR, 0L, 1L);
		ChannelState state = state(Collections.singletonList(cert),
				Collections.<Long>emptyList());
		ChannelPost p = signed(delegate, 0, zero(), "by delegate", delegate);
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(state, p, null));
	}

	@Test
	public void aDelegateUnknownToTheChannelIsRefused() throws Exception {
		ChannelState state = state(Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList());
		ChannelPost p = signed(delegate, 0, zero(), "by delegate", delegate);
		assertEquals(ChannelPostValidator.Result.DELEGATION_NOT_FOUND,
				validator.validate(state, p, null));
		ChannelDelegationCert other = cert(publisher, stranger, HOUR, 0L, 1L);
		assertEquals(ChannelPostValidator.Result.DELEGATION_NOT_FOUND,
				validator.validate(state(Collections.singletonList(other),
						Collections.<Long>emptyList()), p, null));
	}

	@Test
	public void aRevokedDelegationIsRefused() throws Exception {
		ChannelDelegationCert cert = cert(publisher, delegate, HOUR, 0L, 7L);
		ChannelState state = state(Collections.singletonList(cert),
				Collections.singletonList(7L));
		ChannelPost p = signed(delegate, 0, zero(), "by delegate", delegate);
		assertEquals(ChannelPostValidator.Result.DELEGATION_REVOKED,
				validator.validate(state, p, null));
		ChannelState otherRevoked = state(Collections.singletonList(cert),
				Collections.singletonList(8L));
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(otherRevoked, p, null));
	}

	@Test
	public void aPostOutsideTheDelegationWindowIsRefused() throws Exception {
		ChannelPost p = signed(delegate, 0, zero(), "by delegate", delegate);
		long ts = p.getTimestampHourMs();
		ChannelDelegationCert tooLate = cert(publisher, delegate, ts + HOUR,
				0L, 1L);
		assertEquals(ChannelPostValidator.Result.DELEGATION_OUT_OF_WINDOW,
				validator.validate(state(Collections.singletonList(tooLate),
						Collections.<Long>emptyList()), p, null));
		ChannelDelegationCert expired = cert(publisher, delegate, ts - 2 * HOUR,
				ts - HOUR, 1L);
		assertEquals(ChannelPostValidator.Result.DELEGATION_OUT_OF_WINDOW,
				validator.validate(state(Collections.singletonList(expired),
						Collections.<Long>emptyList()), p, null));
		ChannelDelegationCert exact = cert(publisher, delegate, ts, ts, 1L);
		assertEquals(ChannelPostValidator.Result.OK,
				validator.validate(state(Collections.singletonList(exact),
						Collections.<Long>emptyList()), p, null));
	}

	@Test
	public void aCertificateNotSignedByThePublisherIsRefused()
			throws Exception {
		ChannelDelegationCert selfIssued = cert(stranger, delegate, HOUR, 0L,
				1L);
		ChannelState state = state(Collections.singletonList(selfIssued),
				Collections.<Long>emptyList());
		ChannelPost p = signed(delegate, 0, zero(), "by delegate", delegate);
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(state, p, null));
		ChannelDelegationCert good = cert(publisher, delegate, HOUR, 0L, 1L);
		ChannelDelegationCert edited = new ChannelDelegationCert(channelId,
				good.getDelegateeEd25519PubKey(),
				good.getDelegateeMlDsaPubKey(), 0L,
				good.getValidUntilHourMs(), good.getDelegationSeq(),
				good.getSignature());
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(state(Collections.singletonList(edited),
						Collections.<Long>emptyList()), p, null));
	}

	@Test
	public void aDelegatePostMustBeSignedByThatDelegate() throws Exception {
		ChannelDelegationCert cert = cert(publisher, delegate, HOUR, 0L, 1L);
		ChannelState state = state(Collections.singletonList(cert),
				Collections.<Long>emptyList());
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(state,
						signed(publisher, 0, zero(), "x", delegate), null));
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(state,
						signed(stranger, 0, zero(), "x", delegate), null));
		assertEquals(ChannelPostValidator.Result.BAD_SIGNATURE,
				validator.validate(state,
						signed(delegate, 0, zero(), "x", null), null));
	}

	private ChannelState state(List<ChannelDelegationCert> delegations,
			List<Long> revoked) {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) publisher.getPublic();
		return new ChannelState(channelId, new byte[32],
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(),
				"name", "description", null, HOUR, true, null, "", 1L,
				false, -1L, null, null, delegations, revoked, 10L);
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

	private static byte[] flipped(byte[] in) {
		byte[] out = in.clone();
		out[out.length / 2] ^= 0x01;
		return out;
	}
}
