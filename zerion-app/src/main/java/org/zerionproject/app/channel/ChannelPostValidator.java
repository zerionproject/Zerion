package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;
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

	/**
	 * The full check. {@code DELEGATION_REVOKED} is returned only when every
	 * other check passed, including the post signature under the revoked
	 * certificate: the post is authentic and in the chain, but its author's
	 * authorization has been withdrawn, so the caller may keep the post in
	 * the chain and withhold its content. {@code DELEGATION_NOT_FOUND} means
	 * the post claims a delegate this state has never held a certificate
	 * for, active or retired, so the signature cannot be checked at all.
	 */
	Result validate(ChannelState state, ChannelPost post,
			ChannelPost previousOrNull) {
		Result chain = validateChain(post, previousOrNull);
		if (chain != Result.OK) return chain;

		PublicKey signerHybrid = resolveSigner(state, post);
		if (signerHybrid == null) {
			return Result.DELEGATION_NOT_FOUND;
		}

		Result delegationCheck = checkDelegationIfApplicable(state, post);
		if (delegationCheck != Result.OK
				&& delegationCheck != Result.DELEGATION_REVOKED) {
			return delegationCheck;
		}

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
		return delegationCheck;
	}

	/**
	 * Only the chain part of {@link #validate}: body bound, sequence
	 * number and link to the previous post. Used for a post whose signer
	 * cannot be resolved, to decide whether it may be held as a provisional
	 * chain element until a verified successor commits to it.
	 */
	Result validateChain(ChannelPost post, ChannelPost previousOrNull) {
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
		ChannelDelegationCert cert = findDelegation(state, dEd,
				post.getTimestampHourMs());
		if (cert == null) return null;
		return new HybridSignaturePublicKey(cert.getDelegateeEd25519PubKey(),
				cert.getDelegateeMlDsaPubKey());
	}

	private Result checkDelegationIfApplicable(ChannelState state,
			ChannelPost post) {
		if (!post.signedByDelegate()) return Result.OK;
		byte[] dEd = post.getDelegateSignerEd25519PubKey();
		if (dEd == null) return Result.DELEGATION_NOT_FOUND;
		ChannelDelegationCert cert = findDelegation(state, dEd,
				post.getTimestampHourMs());
		if (cert == null) return Result.DELEGATION_NOT_FOUND;
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
		for (Long revokedSeq : state.getRevokedDelegationSeqs()) {
			if (revokedSeq != null
					&& revokedSeq == cert.getDelegationSeq()) {
				return Result.DELEGATION_REVOKED;
			}
		}
		return Result.OK;
	}

	/**
	 * The certificate a post by this delegate is judged under. Of every
	 * certificate held for the key, active or retired, the earliest issued
	 * one whose window covers the post is chosen: the authorization that was
	 * in force when the post was made. That keeps a subscriber's verdict
	 * independent of when it happened to subscribe: a certificate issued
	 * later for the same key neither rescues a post made under a revoked
	 * one nor invalidates a post made under an earlier one. If no held
	 * certificate covers the post, the newest one is returned so the
	 * result is an out-of-window refusal rather than an unknown delegate.
	 */
	@Nullable
	private ChannelDelegationCert findDelegation(ChannelState state,
			byte[] delegateeEd25519, long timestampHourMs) {
		ChannelDelegationCert covering = null;
		ChannelDelegationCert newest = null;
		List<ChannelDelegationCert> all = new ArrayList<>(
				state.getActiveDelegations());
		all.addAll(state.getRetiredDelegations());
		for (ChannelDelegationCert c : all) {
			if (!Arrays.equals(c.getDelegateeEd25519PubKey(),
					delegateeEd25519)) {
				continue;
			}
			if (newest == null
					|| c.getDelegationSeq() > newest.getDelegationSeq()) {
				newest = c;
			}
			if (c.coversTimestamp(timestampHourMs) && (covering == null
					|| c.getDelegationSeq() < covering.getDelegationSeq())) {
				covering = c;
			}
		}
		return covering != null ? covering : newest;
	}
}
