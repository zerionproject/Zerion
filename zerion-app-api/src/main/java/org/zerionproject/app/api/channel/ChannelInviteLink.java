package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class ChannelInviteLink {

	private final byte[] channelId;
	private final byte[] publisherEd25519PubKey;
	@Nullable
	private final byte[] publisherMlDsaPubKey;
	private final boolean publicChannel;
	@Nullable
	private final byte[] joinCapability;
	@Nullable
	private final String onionAddress;
	private final boolean requiresApproval;

	public ChannelInviteLink(byte[] channelId,
			byte[] publisherEd25519PubKey,
			@Nullable byte[] publisherMlDsaPubKey,
			boolean publicChannel,
			@Nullable byte[] joinCapability,
			@Nullable String onionAddress) {
		this(channelId, publisherEd25519PubKey, publisherMlDsaPubKey,
				publicChannel, joinCapability, onionAddress, false);
	}

	public ChannelInviteLink(byte[] channelId,
			byte[] publisherEd25519PubKey,
			@Nullable byte[] publisherMlDsaPubKey,
			boolean publicChannel,
			@Nullable byte[] joinCapability,
			@Nullable String onionAddress,
			boolean requiresApproval) {
		this.channelId = channelId;
		this.publisherEd25519PubKey = publisherEd25519PubKey;
		this.publisherMlDsaPubKey = publisherMlDsaPubKey;
		this.publicChannel = publicChannel;
		this.joinCapability = joinCapability;
		this.onionAddress = onionAddress;
		this.requiresApproval = requiresApproval;
	}

	public boolean requiresApproval() {
		return requiresApproval;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public byte[] getPublisherEd25519PubKey() {
		return publisherEd25519PubKey;
	}

	@Nullable
	public byte[] getPublisherMlDsaPubKey() {
		return publisherMlDsaPubKey;
	}

	public boolean isPublicChannel() {
		return publicChannel;
	}

	@Nullable
	public byte[] getJoinCapability() {
		return joinCapability;
	}

	@Nullable
	public String getOnionAddress() {
		return onionAddress;
	}
}
