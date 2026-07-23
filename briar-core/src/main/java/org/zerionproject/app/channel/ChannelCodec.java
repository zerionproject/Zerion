package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.zerionproject.app.api.channel.ChannelPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
class ChannelCodec {

	private static final java.util.regex.Pattern ONION_V3 =
			java.util.regex.Pattern.compile(
					"^[a-z2-7]{56}(\\.onion)?$");

	private static final String LABEL_MANIFEST_NAME =
			"org.zerionproject/CHANNEL_MANIFEST_NAME";
	private static final String LABEL_MANIFEST_DESC =
			"org.zerionproject/CHANNEL_MANIFEST_DESC";
	private static final String LABEL_POST_BODY =
			"org.zerionproject/CHANNEL_POST_BODY";
	private static final String LABEL_POST_ATTACHMENTS =
			"org.zerionproject/CHANNEL_POST_ATTACHMENTS";

	private final CryptoComponent crypto;

	@Inject
	ChannelCodec(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	byte[] manifestSignedInput(byte[] channelId, byte[] salt,
			byte[] publisherEd25519Pub, byte[] publisherMlDsaPub,
			String name, String description,
			@Nullable byte[] avatarHash, long createdAtHourMs,
			boolean publicChannel, @Nullable byte[] joinCapability,
			String currentOnion, long manifestSeq,
			@Nullable byte[] contentKeyHash,
			List<ChannelDelegationCert> activeDelegations,
			List<Long> revokedDelegationSeqs,
			long pinnedPostSeq, boolean requiresApproval,
			boolean discussionsEnabled) {
		byte[] nameHash = crypto.hash(LABEL_MANIFEST_NAME,
				name.getBytes(StandardCharsets.UTF_8));
		byte[] descHash = crypto.hash(LABEL_MANIFEST_DESC,
				description.getBytes(StandardCharsets.UTF_8));
		byte avatarPresent = (byte) (avatarHash != null ? 1 : 0);
		byte[] avatar = avatarHash != null ? avatarHash
				: new byte[ChannelConstants.PREV_HASH_BYTES];
		byte capabilityPresent = (byte) (joinCapability != null ? 1 : 0);
		byte[] capability = joinCapability != null ? joinCapability
				: new byte[ChannelConstants.JOIN_CAPABILITY_BYTES];
		byte[] onionBytes = currentOnion.toLowerCase(Locale.ROOT)
				.getBytes(StandardCharsets.US_ASCII);
		byte contentKeyHashPresent = (byte) (contentKeyHash != null ? 1 : 0);
		byte[] contentKeyHashBytes = contentKeyHash != null
				? contentKeyHash
				: new byte[ChannelConstants.CONTENT_KEY_HASH_BYTES];
		byte[] delegationsHash = delegationsCanonicalHash(activeDelegations);
		byte[] revokedHash = revokedCanonicalHash(revokedDelegationSeqs);

		ByteBuffer buf = ByteBuffer.allocate(
				channelId.length + salt.length
						+ publisherEd25519Pub.length
						+ publisherMlDsaPub.length
						+ nameHash.length + descHash.length
						+ 1 + avatar.length + 8 + 1
						+ 1 + capability.length
						+ 4 + onionBytes.length + 8
						+ 1 + contentKeyHashBytes.length
						+ delegationsHash.length
						+ revokedHash.length
						+ 8 + 1
						+ (ChannelConstants.DISCUSSIONS_IN_MANIFEST
								? 1 : 0));
		buf.put(channelId);
		buf.put(salt);
		buf.put(publisherEd25519Pub);
		buf.put(publisherMlDsaPub);
		buf.put(nameHash);
		buf.put(descHash);
		buf.put(avatarPresent);
		buf.put(avatar);
		buf.putLong(createdAtHourMs);
		buf.put((byte) (publicChannel ? 1 : 0));
		buf.put(capabilityPresent);
		buf.put(capability);
		buf.putInt(onionBytes.length);
		buf.put(onionBytes);
		buf.putLong(manifestSeq);
		buf.put(contentKeyHashPresent);
		buf.put(contentKeyHashBytes);
		buf.put(delegationsHash);
		buf.put(revokedHash);
		buf.putLong(pinnedPostSeq);
		buf.put((byte) (requiresApproval ? 1 : 0));
		if (ChannelConstants.DISCUSSIONS_IN_MANIFEST) {
			buf.put((byte) (discussionsEnabled ? 1 : 0));
		}
		return buf.array();
	}

	private byte[] delegationsCanonicalHash(
			List<ChannelDelegationCert> active) {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		ByteBuffer countBuf = ByteBuffer.allocate(4);
		countBuf.putInt(active.size());
		sink.write(countBuf.array(), 0, 4);
		for (ChannelDelegationCert c : active) {
			sink.write(c.getDelegateeEd25519PubKey(), 0,
					c.getDelegateeEd25519PubKey().length);
			sink.write(c.getDelegateeMlDsaPubKey(), 0,
					c.getDelegateeMlDsaPubKey().length);
			ByteBuffer numBuf = ByteBuffer.allocate(24);
			numBuf.putLong(c.getValidFromHourMs());
			numBuf.putLong(c.getValidUntilHourMs());
			numBuf.putLong(c.getDelegationSeq());
			sink.write(numBuf.array(), 0, 24);
			sink.write(c.getSignature(), 0, c.getSignature().length);
		}
		return crypto.hash(
				"org.zerionproject/CHANNEL_MANIFEST_DELEGATIONS",
				sink.toByteArray());
	}

	private byte[] revokedCanonicalHash(List<Long> revoked) {
		ByteBuffer buf = ByteBuffer.allocate(4 + revoked.size() * 8);
		buf.putInt(revoked.size());
		for (Long seq : revoked) {
			buf.putLong(seq == null ? 0L : seq);
		}
		return crypto.hash(
				"org.zerionproject/CHANNEL_MANIFEST_REVOKED",
				buf.array());
	}

	byte[] postSignedInput(byte[] channelId, long seqNum,
			byte[] prevHash, long timestampHourMs, String body,
			byte[] attachmentsHash, long ttlMs) {
		byte[] bodyHash = crypto.hash(LABEL_POST_BODY,
				body.getBytes(StandardCharsets.UTF_8));
		ByteBuffer buf = ByteBuffer.allocate(channelId.length + 8
				+ prevHash.length + 8 + bodyHash.length
				+ attachmentsHash.length + 8);
		buf.put(channelId);
		buf.putLong(seqNum);
		buf.put(prevHash);
		buf.putLong(timestampHourMs);
		buf.put(bodyHash);
		buf.put(attachmentsHash);
		buf.putLong(ttlMs);
		return buf.array();
	}

	byte[] applicationSignedInput(byte[] channelId, String displayName,
			long timestampHourMs, byte[] ephemeralAgreementPub) {
		byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(channelId.length
				+ 4 + nameBytes.length + 8
				+ 4 + ephemeralAgreementPub.length);
		buf.put(channelId);
		buf.putInt(nameBytes.length);
		buf.put(nameBytes);
		buf.putLong(timestampHourMs);
		buf.putInt(ephemeralAgreementPub.length);
		buf.put(ephemeralAgreementPub);
		return buf.array();
	}

	byte[] checkApprovalSignedInput(byte[] channelId,
			long timestampHourMs) {
		ByteBuffer buf = ByteBuffer.allocate(channelId.length + 8);
		buf.put(channelId);
		buf.putLong(timestampHourMs);
		return buf.array();
	}

	byte[] tombstoneSignedInput(byte[] channelId, long timestampHourMs) {
		ByteBuffer buf = ByteBuffer.allocate(channelId.length + 8);
		buf.put(channelId);
		buf.putLong(timestampHourMs);
		return buf.array();
	}

	byte[] commentSignedInput(byte[] channelId, long parentPostSeqNum,
			long commentId, String body, String authorName,
			long timestampHourMs) {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		byte[] nameBytes = authorName.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(channelId.length + 8 + 8
				+ 4 + bodyBytes.length + 4 + nameBytes.length + 8);
		buf.put(channelId);
		buf.putLong(parentPostSeqNum);
		buf.putLong(commentId);
		buf.putInt(bodyBytes.length);
		buf.put(bodyBytes);
		buf.putInt(nameBytes.length);
		buf.put(nameBytes);
		buf.putLong(timestampHourMs);
		return buf.array();
	}

	byte[] announceSignedInput(byte[] channelId, String displayName,
			long timestampHourMs) {
		byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(channelId.length
				+ 4 + nameBytes.length + 8);
		buf.put(channelId);
		buf.putInt(nameBytes.length);
		buf.put(nameBytes);
		buf.putLong(timestampHourMs);
		return buf.array();
	}

	byte[] reactionSignedInput(byte[] channelId, long postSeqNum,
			String emoji, long timestampHourMs) {
		byte[] emojiBytes = emoji.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(channelId.length + 8
				+ 4 + emojiBytes.length + 8);
		buf.put(channelId);
		buf.putLong(postSeqNum);
		buf.putInt(emojiBytes.length);
		buf.put(emojiBytes);
		buf.putLong(timestampHourMs);
		return buf.array();
	}

	byte[] delegationSignedInput(byte[] channelId,
			byte[] delegateeEd25519PubKey, byte[] delegateeMlDsaPubKey,
			long validFromHourMs, long validUntilHourMs,
			long delegationSeq) {
		ByteBuffer buf = ByteBuffer.allocate(channelId.length
				+ delegateeEd25519PubKey.length
				+ delegateeMlDsaPubKey.length + 8 + 8 + 8);
		buf.put(channelId);
		buf.put(delegateeEd25519PubKey);
		buf.put(delegateeMlDsaPubKey);
		buf.putLong(validFromHourMs);
		buf.putLong(validUntilHourMs);
		buf.putLong(delegationSeq);
		return buf.array();
	}

	byte[] attachmentsHash(java.util.List<ChannelPost.ChannelAttachment> as) {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		for (ChannelPost.ChannelAttachment a : as) {
			sink.write(a.getBlobHash(), 0, a.getBlobHash().length);
			byte[] sizeBytes = ByteBuffer.allocate(8)
					.putLong(a.getSizeBytes()).array();
			sink.write(sizeBytes, 0, sizeBytes.length);
			byte[] mimeBytes = a.getMimeType()
					.getBytes(StandardCharsets.US_ASCII);
			sink.write(mimeBytes, 0, mimeBytes.length);
			byte[] thumb = a.getThumbnail();
			sink.write(thumb == null ? 0 : 1);
			if (thumb != null) {
				sink.write(thumb, 0, thumb.length);
			}
		}
		return crypto.hash(LABEL_POST_ATTACHMENTS, sink.toByteArray());
	}

	byte[] postCanonicalHash(byte[] channelId, long seqNum,
			byte[] prevHash, long timestampHourMs, String body,
			byte[] attachmentsHash, long ttlMs, byte[] signature) {
		ByteBuffer buf = ByteBuffer.allocate(channelId.length + 8
				+ prevHash.length + 8
				+ body.getBytes(StandardCharsets.UTF_8).length
				+ attachmentsHash.length + 8 + signature.length);
		buf.put(channelId);
		buf.putLong(seqNum);
		buf.put(prevHash);
		buf.putLong(timestampHourMs);
		buf.put(body.getBytes(StandardCharsets.UTF_8));
		buf.put(attachmentsHash);
		buf.putLong(ttlMs);
		buf.put(signature);
		return crypto.hash("org.zerionproject/CHANNEL_POST_CHAIN",
				buf.array());
	}

	String formatInviteLink(byte[] channelId,
			byte[] publisherEd25519Pub,
			@Nullable byte[] publisherMlDsaPub,
			boolean publicChannel,
			@Nullable byte[] joinCapability,
			@Nullable String onionAddress,
			boolean requiresApproval) {
		StringBuilder url = new StringBuilder();
		url.append(ChannelConstants.INVITE_LINK_SCHEME).append("://")
				.append(ChannelConstants.INVITE_LINK_HOST).append("/")
				.append(Base32Util.encode(channelId)).append("/")
				.append(Base32Util.encode(publisherEd25519Pub));
		boolean first = true;
		if (!publicChannel && joinCapability != null
				&& !requiresApproval) {
			url.append(first ? '?' : '&').append(
					ChannelConstants.INVITE_LINK_CAPABILITY_PARAM)
					.append('=').append(Base32Util.encode(joinCapability));
			first = false;
		}
		if (onionAddress != null && !onionAddress.isEmpty()) {
			String onion = onionAddress.toLowerCase(Locale.ROOT);
			if (!ONION_V3.matcher(onion).matches()) {
				throw new IllegalArgumentException(
						"onion address must be base32 v3");
			}
			url.append(first ? '?' : '&').append(
					ChannelConstants.INVITE_LINK_ONION_PARAM)
					.append('=').append(onion);
			first = false;
		}
		if (requiresApproval) {
			url.append(first ? '?' : '&').append(
					ChannelConstants.INVITE_LINK_APPROVAL_PARAM)
					.append("=1");
		}
		return url.toString();
	}

	@Nullable
	ChannelInviteLink parseInviteLink(String url) {
		if (url == null) return null;
		if (url.length() > ChannelConstants.INVITE_LINK_MAX_LENGTH) {
			return null;
		}
		String prefix = ChannelConstants.INVITE_LINK_SCHEME + "://"
				+ ChannelConstants.INVITE_LINK_HOST + "/";
		if (!url.startsWith(prefix)) return null;
		String rest = url.substring(prefix.length());
		String capEncoded = null;
		String onionParam = null;
		boolean approvalFlag = false;
		int q = rest.indexOf('?');
		if (q >= 0) {
			String query = rest.substring(q + 1);
			rest = rest.substring(0, q);
			for (String kv : query.split("&")) {
				int eq = kv.indexOf('=');
				if (eq <= 0) continue;
				String k = kv.substring(0, eq);
				String v = kv.substring(eq + 1);
				if (k.equals(
						ChannelConstants.INVITE_LINK_CAPABILITY_PARAM)) {
					capEncoded = v;
				} else if (k.equals(
						ChannelConstants.INVITE_LINK_ONION_PARAM)) {
					onionParam = v;
				} else if (k.equals(
						ChannelConstants.INVITE_LINK_APPROVAL_PARAM)) {
					approvalFlag = "1".equals(v);
				}
			}
		}
		int slash = rest.indexOf('/');
		if (slash < 0) return null;
		String idEncoded = rest.substring(0, slash);
		String pubEncoded = rest.substring(slash + 1);
		if (pubEncoded.indexOf('/') >= 0) return null;
		try {
			byte[] channelId = Base32Util.decode(idEncoded);
			byte[] publisherEd = Base32Util.decode(pubEncoded);
			if (channelId.length != ChannelConstants.CHANNEL_ID_BYTES) {
				return null;
			}
			if (publisherEd.length != 32) return null;
			byte[] capability = null;
			boolean isPublic = capEncoded == null && !approvalFlag;
			if (capEncoded != null) {
				capability = Base32Util.decode(capEncoded);
				if (capability.length
						!= ChannelConstants.JOIN_CAPABILITY_BYTES) {
					return null;
				}
			}
			String onion = null;
			if (onionParam != null && !onionParam.isEmpty()) {
				if (onionParam.length() > 80) return null;
				onion = onionParam.toLowerCase(Locale.ROOT);
			}
			return new ChannelInviteLink(channelId, publisherEd, null,
					isPublic, capability, onion, approvalFlag);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
