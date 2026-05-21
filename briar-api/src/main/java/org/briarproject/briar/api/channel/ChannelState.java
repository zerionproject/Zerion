package org.briarproject.briar.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public class ChannelState {

	private final byte[] channelId;
	private final byte[] salt;
	private final byte[] publisherEd25519PubKey;
	private final byte[] publisherMlDsaPubKey;
	private final String name;
	private final String description;
	@Nullable
	private final byte[] avatarHash;
	private final long createdAtHourMs;
	private final boolean publicChannel;
	@Nullable
	private final byte[] joinCapability;
	private final String currentOnion;
	private final long manifestSeq;
	private final boolean weArePublisher;
	private final long highestKnownPostSeq;

	public ChannelState(byte[] channelId, byte[] salt,
			byte[] publisherEd25519PubKey, byte[] publisherMlDsaPubKey,
			String name, String description, @Nullable byte[] avatarHash,
			long createdAtHourMs, boolean publicChannel,
			@Nullable byte[] joinCapability, String currentOnion,
			long manifestSeq, boolean weArePublisher,
			long highestKnownPostSeq) {
		this.channelId = channelId;
		this.salt = salt;
		this.publisherEd25519PubKey = publisherEd25519PubKey;
		this.publisherMlDsaPubKey = publisherMlDsaPubKey;
		this.name = name;
		this.description = description;
		this.avatarHash = avatarHash;
		this.createdAtHourMs = createdAtHourMs;
		this.publicChannel = publicChannel;
		this.joinCapability = joinCapability;
		this.currentOnion = currentOnion;
		this.manifestSeq = manifestSeq;
		this.weArePublisher = weArePublisher;
		this.highestKnownPostSeq = highestKnownPostSeq;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public byte[] getSalt() {
		return salt;
	}

	public byte[] getPublisherEd25519PubKey() {
		return publisherEd25519PubKey;
	}

	public byte[] getPublisherMlDsaPubKey() {
		return publisherMlDsaPubKey;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	@Nullable
	public byte[] getAvatarHash() {
		return avatarHash;
	}

	public long getCreatedAtHourMs() {
		return createdAtHourMs;
	}

	public boolean isPublicChannel() {
		return publicChannel;
	}

	@Nullable
	public byte[] getJoinCapability() {
		return joinCapability;
	}

	public String getCurrentOnion() {
		return currentOnion;
	}

	public long getManifestSeq() {
		return manifestSeq;
	}

	public boolean weArePublisher() {
		return weArePublisher;
	}

	public long getHighestKnownPostSeq() {
		return highestKnownPostSeq;
	}
}
