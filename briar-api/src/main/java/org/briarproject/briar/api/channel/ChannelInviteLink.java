package org.briarproject.briar.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class ChannelInviteLink {

	private final byte[] channelId;
	private final byte[] publisherEd25519PubKey;
	private final boolean publicChannel;
	@Nullable
	private final byte[] joinCapability;

	public ChannelInviteLink(byte[] channelId,
			byte[] publisherEd25519PubKey, boolean publicChannel,
			@Nullable byte[] joinCapability) {
		this.channelId = channelId;
		this.publisherEd25519PubKey = publisherEd25519PubKey;
		this.publicChannel = publicChannel;
		this.joinCapability = joinCapability;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public byte[] getPublisherEd25519PubKey() {
		return publisherEd25519PubKey;
	}

	public boolean isPublicChannel() {
		return publicChannel;
	}

	@Nullable
	public byte[] getJoinCapability() {
		return joinCapability;
	}
}
