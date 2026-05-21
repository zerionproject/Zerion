package org.briarproject.briar.channel;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.briar.api.channel.ChannelConstants;
import org.briarproject.briar.api.channel.ChannelInviteLink;
import org.briarproject.briar.api.channel.ChannelPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
class ChannelCodec {

	private static final String LABEL_MANIFEST_NAME =
			"org.briarproject.zerion/CHANNEL_MANIFEST_NAME";
	private static final String LABEL_MANIFEST_DESC =
			"org.briarproject.zerion/CHANNEL_MANIFEST_DESC";
	private static final String LABEL_POST_BODY =
			"org.briarproject.zerion/CHANNEL_POST_BODY";
	private static final String LABEL_POST_ATTACHMENTS =
			"org.briarproject.zerion/CHANNEL_POST_ATTACHMENTS";

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
			String currentOnion, long manifestSeq) {
		byte[] nameHash = crypto.hash(LABEL_MANIFEST_NAME,
				name.getBytes(StandardCharsets.UTF_8));
		byte[] descHash = crypto.hash(LABEL_MANIFEST_DESC,
				description.getBytes(StandardCharsets.UTF_8));
		byte[] avatar = avatarHash != null ? avatarHash
				: new byte[ChannelConstants.PREV_HASH_BYTES];
		byte[] capability = joinCapability != null ? joinCapability
				: new byte[ChannelConstants.JOIN_CAPABILITY_BYTES];
		byte[] onionBytes = currentOnion.toLowerCase(Locale.ROOT)
				.getBytes(StandardCharsets.US_ASCII);

		ByteBuffer buf = ByteBuffer.allocate(
				channelId.length + salt.length
						+ publisherEd25519Pub.length
						+ publisherMlDsaPub.length
						+ nameHash.length + descHash.length
						+ avatar.length + 8 + 1
						+ capability.length
						+ 4 + onionBytes.length + 8);
		buf.put(channelId);
		buf.put(salt);
		buf.put(publisherEd25519Pub);
		buf.put(publisherMlDsaPub);
		buf.put(nameHash);
		buf.put(descHash);
		buf.put(avatar);
		buf.putLong(createdAtHourMs);
		buf.put((byte) (publicChannel ? 1 : 0));
		buf.put(capability);
		buf.putInt(onionBytes.length);
		buf.put(onionBytes);
		buf.putLong(manifestSeq);
		return buf.array();
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
		return crypto.hash("org.briarproject.zerion/CHANNEL_POST_CHAIN",
				buf.array());
	}

	String formatInviteLink(byte[] channelId,
			byte[] publisherEd25519Pub, boolean publicChannel,
			@Nullable byte[] joinCapability) {
		String url = ChannelConstants.INVITE_LINK_SCHEME + "://"
				+ ChannelConstants.INVITE_LINK_HOST + "/"
				+ Base32Util.encode(channelId) + "/"
				+ Base32Util.encode(publisherEd25519Pub);
		if (!publicChannel && joinCapability != null) {
			url += "?" + ChannelConstants.INVITE_LINK_CAPABILITY_PARAM
					+ "=" + Base32Util.encode(joinCapability);
		}
		return url;
	}

	@Nullable
	ChannelInviteLink parseInviteLink(String url) {
		if (url == null) return null;
		String prefix = ChannelConstants.INVITE_LINK_SCHEME + "://"
				+ ChannelConstants.INVITE_LINK_HOST + "/";
		if (!url.startsWith(prefix)) return null;
		String rest = url.substring(prefix.length());
		String capEncoded = null;
		int q = rest.indexOf('?');
		if (q >= 0) {
			String query = rest.substring(q + 1);
			rest = rest.substring(0, q);
			String pref = ChannelConstants.INVITE_LINK_CAPABILITY_PARAM
					+ "=";
			if (query.startsWith(pref)) {
				capEncoded = query.substring(pref.length());
			}
		}
		int slash = rest.indexOf('/');
		if (slash < 0) return null;
		String idEncoded = rest.substring(0, slash);
		String pubEncoded = rest.substring(slash + 1);
		try {
			byte[] channelId = Base32Util.decode(idEncoded);
			byte[] publisherEd = Base32Util.decode(pubEncoded);
			if (channelId.length != ChannelConstants.CHANNEL_ID_BYTES) {
				return null;
			}
			if (publisherEd.length != 32) return null;
			byte[] capability = null;
			boolean isPublic = capEncoded == null;
			if (capEncoded != null) {
				capability = Base32Util.decode(capEncoded);
				if (capability.length
						!= ChannelConstants.JOIN_CAPABILITY_BYTES) {
					return null;
				}
			}
			return new ChannelInviteLink(channelId, publisherEd,
					isPublic, capability);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
