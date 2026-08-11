package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.inject.Inject;

@NotNullByDefault
class ChannelPostValidator {

	enum Result {
		OK,
		CHAIN_BROKEN,
		BAD_SIGNATURE,
		DELEGATION_NOT_FOUND,
		DELEGATION_REVOKED,
		DELEGATION_OUT_OF_WINDOW,
		BODY_TOO_LARGE,
		SEQ_OUT_OF_ORDER
	}

	private final ChannelCodec codec;
	private final ChannelSignatures signatures;
	private final ChannelChainVerifier chainVerifier;

	@Inject
	ChannelPostValidator(ChannelCodec codec,
			ChannelSignatures signatures,
			ChannelChainVerifier chainVerifier) {
		this.codec = codec;
		this.signatures = signatures;
		this.chainVerifier = chainVerifier;
	}

	Result validate(ChannelState state, ChannelPost post,
			ChannelPost previousOrNull) {
		if (post.getBody().length()
				> ChannelConstants.MAX_POST_BODY_CHARS) {
			return Result.BODY_TOO_LARGE;
		}
		if (previousOrNull == null) {
			if (post.getSeqNum() != 0L) return Result.SEQ_OUT_OF_ORDER;
			byte[] zero = new byte[ChannelConstants.PREV_HASH_BYTES];
			if (!Arrays.equals(post.getPrevHash(), zero)) {
				return Result.CHAIN_BROKEN;
			}
		} else {
			if (post.getSeqNum() != previousOrNull.getSeqNum() + 1L) {
				return Result.SEQ_OUT_OF_ORDER;
			}
			byte[] expectedPrev = chainVerifier.hashOf(previousOrNull);
			if (!Arrays.equals(post.getPrevHash(), expectedPrev)) {
				return Result.CHAIN_BROKEN;
			}
		}

		PublicKey signerHybrid = resolveSigner(state, post);
		if (signerHybrid == null) {
			return Result.DELEGATION_NOT_FOUND;
		}

		Result delegationCheck = checkDelegationIfApplicable(state, post);
		if (delegationCheck != Result.OK) return delegationCheck;

		byte[] signedInput = codec.postSignedInput(
				post.getChannelId(), post.getSeqNum(),
				post.getPrevHash(), post.getTimestampHourMs(),
				post.getBody(),
				codec.attachmentsHash(post.getAttachments()),
				post.getTtlMs());
		if (!signatures.verifyPost(post.getSignature(),
				signedInput, signerHybrid)) {
			return Result.BAD_SIGNATURE;
		}
		return Result.OK;
	}

	@javax.annotation.Nullable
	private PublicKey resolveSigner(ChannelState state, ChannelPost post) {
		if (!post.signedByDelegate()) {
			return new HybridSignaturePublicKey(
					state.getPublisherEd25519PubKey(),
					state.getPublisherMlDsaPubKey());
		}
		byte[] dEd = post.getDelegateSignerEd25519PubKey();
		if (dEd == null) return null;
		ChannelDelegationCert cert = findDelegation(state, dEd);
		if (cert == null) return null;
		return new HybridSignaturePublicKey(cert.getDelegateeEd25519PubKey(),
				cert.getDelegateeMlDsaPubKey());
	}

	private Result checkDelegationIfApplicable(ChannelState state,
			ChannelPost post) {
		if (!post.signedByDelegate()) return Result.OK;
		byte[] dEd = post.getDelegateSignerEd25519PubKey();
		if (dEd == null) return Result.DELEGATION_NOT_FOUND;
		ChannelDelegationCert cert = findDelegation(state, dEd);
		if (cert == null) return Result.DELEGATION_NOT_FOUND;
		for (Long revokedSeq : state.getRevokedDelegationSeqs()) {
			if (revokedSeq != null
					&& revokedSeq == cert.getDelegationSeq()) {
				return Result.DELEGATION_REVOKED;
			}
		}
		if (!cert.coversTimestamp(post.getTimestampHourMs())) {
			return Result.DELEGATION_OUT_OF_WINDOW;
		}
		byte[] certSignedInput = codec.delegationSignedInput(
				cert.getChannelId(),
				cert.getDelegateeEd25519PubKey(),
				cert.getDelegateeMlDsaPubKey(),
				cert.getValidFromHourMs(),
				cert.getValidUntilHourMs(),
				cert.getDelegationSeq());
		PublicKey owner = new HybridSignaturePublicKey(
				state.getPublisherEd25519PubKey(),
				state.getPublisherMlDsaPubKey());
		if (!signatures.verifyDelegation(cert.getSignature(),
				certSignedInput, owner)) {
			return Result.BAD_SIGNATURE;
		}
		return Result.OK;
	}

	@javax.annotation.Nullable
	private ChannelDelegationCert findDelegation(ChannelState state,
			byte[] delegateeEd25519) {
		for (ChannelDelegationCert c : state.getActiveDelegations()) {
			if (Arrays.equals(c.getDelegateeEd25519PubKey(),
					delegateeEd25519)) {
				return c;
			}
		}
		return null;
	}
}
