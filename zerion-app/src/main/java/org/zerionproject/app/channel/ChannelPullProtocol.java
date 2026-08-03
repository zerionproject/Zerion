package org.zerionproject.app.channel;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
class ChannelPullProtocol {

	private final ChannelCodec codec;
	private final ChannelPullCodec pullCodec;
	private final ChannelHmacChallenge hmacChallenge;
	private final ChannelContentKey contentKey;
	private final ChannelPostValidator validator;
	private final ChannelSignatures signatures;
	private final CryptoComponent crypto;

	@Inject
	ChannelPullProtocol(ChannelCodec codec, ChannelPullCodec pullCodec,
			ChannelHmacChallenge hmacChallenge,
			ChannelContentKey contentKey,
			ChannelPostValidator validator,
			ChannelSignatures signatures,
			CryptoComponent crypto) {
		this.codec = codec;
		this.pullCodec = pullCodec;
		this.hmacChallenge = hmacChallenge;
		this.contentKey = contentKey;
		this.validator = validator;
		this.signatures = signatures;
		this.crypto = crypto;
	}

	byte[] buildBootstrapRequest(byte[] channelId) throws IOException {
		return pullCodec.encodePullRequest(channelId, -1L, null, null);
	}

	byte[] buildAuthenticatedRequest(byte[] channelId,
			long sinceSeqNum, byte[] capability, byte[] publisherNonce)
			throws IOException {
		byte[] response = hmacChallenge.respond(capability,
				publisherNonce, channelId);
		return pullCodec.encodePullRequest(channelId, sinceSeqNum,
				response, publisherNonce);
	}

	byte[] buildResponseAsPublisher(ChannelState state,
			byte[] publisherEd25519, byte[] publisherMlDsa,
			byte[] manifestSignature, boolean discussionsEnabled,
			List<ChannelPost> postsToSend,
			@Nullable byte[] contentKeyEnvelope,
			List<String> neighbourHints,
			List<org.zerionproject.app.api.channel.ChannelReaction>
					reactions,
			List<org.zerionproject.app.api.channel.ChannelComment>
					comments) throws IOException {
		byte[] wireJoinCapability = state.isPublicChannel()
				? state.getJoinCapability() : null;
		BdfDictionary manifestDict = pullCodec.encodeManifest(
				state.getChannelId(), state.getSalt(),
				publisherEd25519, publisherMlDsa, state.getName(),
				state.getDescription(), state.getAvatarHash(),
				state.getCreatedAtHourMs(), state.isPublicChannel(),
				wireJoinCapability, state.getCurrentOnion(),
				state.getManifestSeq(), state.getContentKeyHash(),
				state.getActiveDelegations(),
				state.getRevokedDelegationSeqs(),
				state.getPinnedPostSeq(), state.requiresApproval(),
				discussionsEnabled, manifestSignature);
		return pullCodec.encodePullResponse(manifestDict, postsToSend,
				contentKeyEnvelope, neighbourHints, reactions,
				comments);
	}

	ProcessResult processSubscriberResponse(byte[] responseBytes,
			ChannelState localState, List<ChannelPost> existingPosts,
			@Nullable byte[] capability) {
		ChannelPullCodec.PullResponse resp;
		try {
			resp = pullCodec.decodePullResponse(responseBytes,
					localState.getChannelId());
		} catch (IOException e) {
			return ProcessResult.failure("decode failed: "
					+ e.getMessage());
		}

		byte[] envContentKey = null;
		if (resp.contentKeyEnvelope != null && capability != null) {
			try {
				envContentKey = contentKey.unwrapContentKey(capability,
						localState.getChannelId(),
						resp.contentKeyEnvelope);
			} catch (GeneralSecurityException e) {
				return ProcessResult.failure(
						"content key envelope unwrap failed");
			}
			byte[] manifestKeyHash;
			try {
				manifestKeyHash =
						resp.manifest.getOptionalRaw("contentKeyHash");
			} catch (org.zerionproject.core.api.FormatException e) {
				return ProcessResult.failure("manifest malformed");
			}
			if (manifestKeyHash != null && !java.util.Arrays.equals(
					manifestKeyHash,
					contentKey.hashContentKey(envContentKey))) {
				return ProcessResult.failure(
						"content key hash mismatch");
			}
		}

		ChannelState workingState = localState;
		if (envContentKey != null
				&& localState.getContentKey() == null) {
			workingState = withContentKey(localState, envContentKey);
		}

		ChannelState mergedState = mergeManifestIntoLocal(workingState,
				resp.manifest, envContentKey);
		if (mergedState == null) {
			return ProcessResult.failure("manifest merge rejected");
		}
		if (mergedState == workingState
				&& workingState != localState) {
			mergedState = workingState;
		}

		List<ChannelPost> accepted = new ArrayList<>();
		ChannelPost prev = existingPosts.isEmpty() ? null
				: existingPosts.get(existingPosts.size() - 1);
		long lastKnownSeq = prev == null ? -1L : prev.getSeqNum();
		for (ChannelPost incoming : resp.newPosts) {
			if (incoming.getSeqNum() <= lastKnownSeq) {
				continue;
			}
			ChannelPostValidator.Result vr = validator.validate(
					mergedState, incoming, prev);
			if (vr != ChannelPostValidator.Result.OK) {
				break;
			}
			accepted.add(incoming);
			prev = incoming;
		}

		boolean wireDiscussions;
		try {
			wireDiscussions = resp.manifest.getBoolean(
					"discussionsEnabled", true);
		} catch (FormatException e) {
			wireDiscussions = true;
		}
		return ProcessResult.success(mergedState, accepted,
				resp.neighbourHints, resp.reactions, resp.comments,
				wireDiscussions);
	}

	@Nullable
	private ChannelState withContentKey(ChannelState s,
			byte[] freshContentKey) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq(), s.weArePublisher(),
				s.getHighestKnownPostSeq(),
				s.getContentKeyHash(),
				freshContentKey,
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(),
				s.getOnionPrivateKey(),
				s.getPinnedPostSeq(),
				s.requiresApproval());
	}

	private ChannelState mergeManifestIntoLocal(ChannelState local,
			BdfDictionary manifest, @Nullable byte[] freshContentKey) {
		try {
			byte[] wirePubEd = manifest.getRaw("publisherEd25519");
			byte[] wirePubMl = manifest.getRaw("publisherMlDsa");
			if (!java.util.Arrays.equals(wirePubEd,
					local.getPublisherEd25519PubKey())) {
				return null;
			}
			byte[] localMlDsa = local.getPublisherMlDsaPubKey();
			if (localMlDsa != null && localMlDsa.length > 0
					&& !java.util.Arrays.equals(wirePubMl, localMlDsa)) {
				return null;
			}
			byte[] wireSig = manifest.getRaw("signature");
			byte[] wireSalt = manifest.getRaw("salt");
			byte[] wireAvatar = manifest.getOptionalRaw("avatarHash");
			byte[] wireCapRaw = manifest.getOptionalRaw("joinCapability");
			byte[] wireCap = wireCapRaw != null
					? wireCapRaw : local.getJoinCapability();
			String wireName = manifest.getString("name");
			String wireDesc = manifest.getString("description");
			long wireCreatedAt = manifest.getLong("createdAtHourMs");
			boolean wirePublic = manifest.getBoolean("publicChannel");
			String wireOnion = manifest.getString("currentOnion");
			long incomingSeq = manifest.getLong("manifestSeq");
			byte[] wireChannelId = manifest.getRaw("channelId");
			if (!java.util.Arrays.equals(wireChannelId,
					local.getChannelId())) {
				return null;
			}
			byte[] derivedChannelId = crypto.hash(
					"org.zerionproject/CHANNEL_ID",
					new HybridSignaturePublicKey(wirePubEd, wirePubMl)
							.getEncoded(),
					wireSalt);
			if (!java.util.Arrays.equals(derivedChannelId,
					local.getChannelId())) {
				return null;
			}
			List<ChannelDelegationCert> active = new ArrayList<>();
			for (Object o : manifest.getList("activeDelegations",
					new org.zerionproject.core.api.data.BdfList())) {
				if (!(o instanceof BdfDictionary)) continue;
				BdfDictionary cd = (BdfDictionary) o;
				active.add(new ChannelDelegationCert(
						cd.getRaw("channelId"),
						cd.getRaw("delegateeEd25519"),
						cd.getRaw("delegateeMlDsa"),
						cd.getLong("validFromHourMs"),
						cd.getLong("validUntilHourMs"),
						cd.getLong("delegationSeq"),
						cd.getRaw("signature")));
			}
			List<Long> revoked = new ArrayList<>();
			for (Object o : manifest.getList("revokedDelegationSeqs",
					new org.zerionproject.core.api.data.BdfList())) {
				if (o instanceof Long) revoked.add((Long) o);
			}
			byte[] contentKeyHash =
					manifest.getOptionalRaw("contentKeyHash");
			long wirePinnedPostSeq = manifest.getLong("pinnedPostSeq",
					ChannelState.NO_PINNED_POST);
			boolean wireRequiresApproval = manifest.getBoolean(
					"requiresApproval", false);
			boolean wireDiscussionsEnabled = manifest.getBoolean(
					"discussionsEnabled", true);
			byte[] signedInput = codec.manifestSignedInput(
					local.getChannelId(), wireSalt, wirePubEd, wirePubMl,
					wireName, wireDesc, wireAvatar, wireCreatedAt,
					wirePublic, wireCap, wireOnion, incomingSeq,
					contentKeyHash, active, revoked, wirePinnedPostSeq,
					wireRequiresApproval, wireDiscussionsEnabled);
			org.zerionproject.core.api.crypto.HybridSignaturePublicKey
					pub = new org.zerionproject.core.api.crypto
					.HybridSignaturePublicKey(wirePubEd, wirePubMl);
			if (!signatures.verifyManifest(wireSig, signedInput, pub)) {
				return null;
			}
			if (incomingSeq <= local.getManifestSeq()) {
				return local;
			}
			byte[] joinCapRaw = manifest.getOptionalRaw("joinCapability");
			byte[] joinCap = joinCapRaw != null
					? joinCapRaw : local.getJoinCapability();
			return new ChannelState(local.getChannelId(),
					manifest.getRaw("salt"),
					manifest.getRaw("publisherEd25519"),
					manifest.getRaw("publisherMlDsa"),
					manifest.getString("name"),
					manifest.getString("description"),
					manifest.getOptionalRaw("avatarHash"),
					manifest.getLong("createdAtHourMs"),
					manifest.getBoolean("publicChannel"),
					joinCap,
					manifest.getString("currentOnion"),
					incomingSeq,
					local.weArePublisher(),
					local.getHighestKnownPostSeq(),
					contentKeyHash,
					freshContentKey != null
							? freshContentKey
							: local.getContentKey(),
					active,
					revoked,
					local.getNextDelegationSeq(),
					local.getOnionPrivateKey(),
					wirePinnedPostSeq,
					wireRequiresApproval);
		} catch (FormatException e) {
			return null;
		}
	}

	@NotNullByDefault
	static final class ProcessResult {
		final boolean ok;
		@Nullable
		final ChannelState mergedState;
		final List<ChannelPost> acceptedPosts;
		final List<String> neighbourHints;
		final List<org.zerionproject.app.api.channel.ChannelReaction>
				reactions;
		final List<org.zerionproject.app.api.channel.ChannelComment>
				comments;
		final boolean discussionsEnabled;
		final String error;

		private ProcessResult(boolean ok,
				@Nullable ChannelState mergedState,
				List<ChannelPost> acceptedPosts,
				List<String> neighbourHints,
				List<org.zerionproject.app.api.channel.ChannelReaction>
						reactions,
				List<org.zerionproject.app.api.channel.ChannelComment>
						comments,
				boolean discussionsEnabled,
				String error) {
			this.ok = ok;
			this.mergedState = mergedState;
			this.acceptedPosts = acceptedPosts;
			this.neighbourHints = neighbourHints;
			this.reactions = reactions;
			this.comments = comments;
			this.discussionsEnabled = discussionsEnabled;
			this.error = error;
		}

		static ProcessResult success(ChannelState mergedState,
				List<ChannelPost> acceptedPosts,
				List<String> neighbourHints,
				List<org.zerionproject.app.api.channel.ChannelReaction>
						reactions,
				List<org.zerionproject.app.api.channel.ChannelComment>
						comments,
				boolean discussionsEnabled) {
			return new ProcessResult(true, mergedState, acceptedPosts,
					neighbourHints, reactions, comments,
					discussionsEnabled, "");
		}

		static ProcessResult failure(String error) {
			return new ProcessResult(false, null,
					Collections.<ChannelPost>emptyList(),
					Collections.<String>emptyList(),
					Collections.<org.zerionproject.app.api.channel
							.ChannelReaction>emptyList(),
					Collections.<org.zerionproject.app.api.channel
							.ChannelComment>emptyList(),
					true, error);
		}
	}
}
