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

	Result validate(ChannelState state, ChannelPost post,
			@Nullable ChannelPost previousOrNull) {
		return validate(state, post, tipOf(previousOrNull), false);
	}

	Result validate(ChannelState state, ChannelPost post,
			@Nullable ChannelChainTip tip, boolean gapAllowed) {
		Result chain = validateChain(post, tip, gapAllowed);
		if (chain != Result.OK) return chain;
		return validateSigner(state, post);
	}

	Result validateSigner(ChannelState state, ChannelPost post) {
		PublicKey signerHybrid = resolveSigner(state, post);
		if (signerHybrid == null) {
			return Result.DELEGATION_NOT_FOUND;
		}

		Result delegationCheck = checkDelegationIfApplicable(state, post);
		if (delegationCheck != Result.OK
				&& delegationCheck != Result.DELEGATION_REVOKED) {
			return delegationCheck;
		}

		if (!verifySignature(post, signerHybrid)) {
			return Result.BAD_SIGNATURE;
		}
		return delegationCheck;
	}

	private boolean verifySignature(ChannelPost post, PublicKey signer) {
		if (post.getFormatVersion() == ChannelPost.FORMAT_V2) {
			byte[] salt = post.getSalt();
			if (salt == null
					|| salt.length != ChannelConstants.POST_SALT_BYTES) {
				return false;
			}
			return signatures.verifyPostV2(post.getSignature(),
					codec.signedInputOf(post), signer);
		}
		if (post.getFormatVersion() != ChannelPost.FORMAT_LEGACY) {
			return false;
		}
		return signatures.verifyPost(post.getSignature(),
				codec.signedInputOf(post), signer);
	}

	Result validateChain(ChannelPost post,
			@Nullable ChannelPost previousOrNull) {
		return validateChain(post, tipOf(previousOrNull), false);
	}

	Result validateChain(ChannelPost post, @Nullable ChannelChainTip tip,
			boolean gapAllowed) {
		if (post.getBody().length()
				> ChannelConstants.MAX_POST_BODY_CHARS) {
			return Result.BODY_TOO_LARGE;
		}
		if (post.getSeqNum() < 0L
				|| post.getSeqNum() > ChannelConstants.MAX_SEQUENCE_NUMBER) {
			return Result.SEQ_OUT_OF_ORDER;
		}
		if (tip == null) {
			if (post.getSeqNum() == 0L) {
				byte[] zero = new byte[ChannelConstants.PREV_HASH_BYTES];
				if (!Arrays.equals(post.getPrevHash(), zero)) {
					return Result.CHAIN_BROKEN;
				}
				return Result.OK;
			}
			return gapAllowed && post.getSeqNum() > 0L
					? Result.OK : Result.SEQ_OUT_OF_ORDER;
		}
		if (post.getSeqNum() <= tip.seqNum) return Result.SEQ_OUT_OF_ORDER;
		if (post.getSeqNum() == tip.seqNum + 1L) {
			if (!gapAllowed && tip.hash != null
					&& !Arrays.equals(post.getPrevHash(), tip.hash)) {
				return Result.CHAIN_BROKEN;
			}
			return Result.OK;
		}
		return gapAllowed ? Result.OK : Result.SEQ_OUT_OF_ORDER;
	}

	static boolean linksTo(ChannelPost post, @Nullable ChannelChainTip tip) {
		if (tip == null || tip.hash == null
				|| post.getSeqNum() != tip.seqNum + 1L) {
			return true;
		}
		return Arrays.equals(post.getPrevHash(), tip.hash);
	}

	@Nullable
	private ChannelChainTip tipOf(@Nullable ChannelPost previousOrNull) {
		if (previousOrNull == null) return null;
		return new ChannelChainTip(previousOrNull.getSeqNum(),
				chainVerifier.hashOf(previousOrNull));
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

	@Nullable
	private ChannelDelegationCert findDelegation(ChannelState state,
			byte[] delegateeEd25519, long timestampHourMs) {
		ChannelDelegationCert inForce = null;
		ChannelDelegationCert revokedCovering = null;
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
			if (!c.coversTimestamp(timestampHourMs)) continue;
			if (isRevoked(state, c)) {
				if (revokedCovering == null || c.getDelegationSeq()
						< revokedCovering.getDelegationSeq()) {
					revokedCovering = c;
				}
			} else if (inForce == null
					|| c.getDelegationSeq() < inForce.getDelegationSeq()) {
				inForce = c;
			}
		}
		if (inForce != null) return inForce;
		return revokedCovering != null ? revokedCovering : newest;
	}

	private static boolean isRevoked(ChannelState state,
			ChannelDelegationCert c) {
		for (Long revokedSeq : state.getRevokedDelegationSeqs()) {
			if (revokedSeq != null && revokedSeq == c.getDelegationSeq()) {
				return true;
			}
		}
		return false;
	}
}
