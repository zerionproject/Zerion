package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.briar.api.channel.ChannelDelegationCert;
import org.briarproject.briar.api.channel.ChannelPost;
import org.briarproject.briar.api.channel.ChannelState;
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

	@Inject
	ChannelPullProtocol(ChannelCodec codec, ChannelPullCodec pullCodec,
			ChannelHmacChallenge hmacChallenge,
			ChannelContentKey contentKey,
			ChannelPostValidator validator) {
		this.codec = codec;
		this.pullCodec = pullCodec;
		this.hmacChallenge = hmacChallenge;
		this.contentKey = contentKey;
		this.validator = validator;
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
			byte[] manifestSignature,
			List<ChannelPost> postsToSend,
			@Nullable byte[] contentKeyEnvelope,
			List<String> neighbourHints) throws IOException {
		BdfDictionary manifestDict = pullCodec.encodeManifest(
				state.getChannelId(), state.getSalt(),
				publisherEd25519, publisherMlDsa, state.getName(),
				state.getDescription(), state.getAvatarHash(),
				state.getCreatedAtHourMs(), state.isPublicChannel(),
				state.getJoinCapability(), state.getCurrentOnion(),
				state.getManifestSeq(), state.getContentKeyHash(),
				state.getActiveDelegations(),
				state.getRevokedDelegationSeqs(), manifestSignature);
		return pullCodec.encodePullResponse(manifestDict, postsToSend,
				contentKeyEnvelope, neighbourHints);
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
		}
		byte[] effectiveContentKey = envContentKey != null
				? envContentKey : localState.getContentKey();

		ChannelState mergedState = mergeManifestIntoLocal(localState,
				resp.manifest, envContentKey);
		if (mergedState == null) {
			return ProcessResult.failure("manifest merge rejected");
		}

		List<ChannelPost> accepted = new ArrayList<>();
		ChannelPost prev = existingPosts.isEmpty() ? null
				: existingPosts.get(existingPosts.size() - 1);
		for (ChannelPost incoming : resp.newPosts) {
			ChannelPost decrypted = incoming;
			if (!mergedState.isPublicChannel()
					&& effectiveContentKey != null) {
				try {
					String plain = contentKey.decryptBody(
							effectiveContentKey,
							incoming.getChannelId(),
							incoming.getSeqNum(),
							incoming.getBody().getBytes(
									java.nio.charset.StandardCharsets
											.ISO_8859_1));
					decrypted = withDecryptedBody(incoming, plain);
				} catch (GeneralSecurityException e) {
					return ProcessResult.failure(
							"post body decrypt failed at seq "
									+ incoming.getSeqNum());
				}
			}
			ChannelPostValidator.Result vr = validator.validate(
					mergedState, decrypted, prev);
			if (vr != ChannelPostValidator.Result.OK) {
				return ProcessResult.failure(
						"post seq " + decrypted.getSeqNum()
								+ " rejected: " + vr.name());
			}
			accepted.add(decrypted);
			prev = decrypted;
		}

		return ProcessResult.success(mergedState, accepted,
				resp.neighbourHints);
	}

	@Nullable
	private ChannelState mergeManifestIntoLocal(ChannelState local,
			BdfDictionary manifest, @Nullable byte[] freshContentKey) {
		try {
			long incomingSeq = manifest.getLong("manifestSeq");
			if (incomingSeq < local.getManifestSeq()) {
				return local;
			}
			List<ChannelDelegationCert> active = new ArrayList<>();
			for (Object o : manifest.getList("activeDelegations",
					new org.briarproject.bramble.api.data.BdfList())) {
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
					new org.briarproject.bramble.api.data.BdfList())) {
				if (o instanceof Long) revoked.add((Long) o);
			}
			byte[] contentKeyHash =
					manifest.getOptionalRaw("contentKeyHash");
			byte[] joinCap = manifest.getOptionalRaw("joinCapability");
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
					local.getNextDelegationSeq());
		} catch (FormatException e) {
			return null;
		}
	}

	private ChannelPost withDecryptedBody(ChannelPost wireForm,
			String plainBody) {
		return new ChannelPost(wireForm.getChannelId(),
				wireForm.getSeqNum(), wireForm.getPrevHash(),
				wireForm.getTimestampHourMs(), plainBody,
				wireForm.getAttachments(), wireForm.getTtlMs(),
				wireForm.getSignature(), false,
				wireForm.getDelegateSignerEd25519PubKey(),
				wireForm.getDelegateSignerMlDsaPubKey());
	}

	@NotNullByDefault
	static final class ProcessResult {
		final boolean ok;
		@Nullable
		final ChannelState mergedState;
		final List<ChannelPost> acceptedPosts;
		final List<String> neighbourHints;
		final String error;

		private ProcessResult(boolean ok,
				@Nullable ChannelState mergedState,
				List<ChannelPost> acceptedPosts,
				List<String> neighbourHints, String error) {
			this.ok = ok;
			this.mergedState = mergedState;
			this.acceptedPosts = acceptedPosts;
			this.neighbourHints = neighbourHints;
			this.error = error;
		}

		static ProcessResult success(ChannelState mergedState,
				List<ChannelPost> acceptedPosts,
				List<String> neighbourHints) {
			return new ProcessResult(true, mergedState, acceptedPosts,
					neighbourHints, "");
		}

		static ProcessResult failure(String error) {
			return new ProcessResult(false, null,
					Collections.<ChannelPost>emptyList(),
					Collections.<String>emptyList(), error);
		}
	}
}
