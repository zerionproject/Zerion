package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.List;

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
	@Nullable
	private final byte[] contentKeyHash;
	@Nullable
	private final byte[] contentKey;
	private final List<ChannelDelegationCert> activeDelegations;
	private final List<Long> revokedDelegationSeqs;
	private final long nextDelegationSeq;
	@Nullable
	private final String onionPrivateKey;
	private final long pinnedPostSeq;
	private final boolean requiresApproval;

	public static final long NO_PINNED_POST = -1L;

	public ChannelState(byte[] channelId, byte[] salt,
			byte[] publisherEd25519PubKey, byte[] publisherMlDsaPubKey,
			String name, String description, @Nullable byte[] avatarHash,
			long createdAtHourMs, boolean publicChannel,
			@Nullable byte[] joinCapability, String currentOnion,
			long manifestSeq, boolean weArePublisher,
			long highestKnownPostSeq) {
		this(channelId, salt, publisherEd25519PubKey, publisherMlDsaPubKey,
				name, description, avatarHash, createdAtHourMs,
				publicChannel, joinCapability, currentOnion, manifestSeq,
				weArePublisher, highestKnownPostSeq, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null, NO_PINNED_POST,
				false);
	}

	public ChannelState(byte[] channelId, byte[] salt,
			byte[] publisherEd25519PubKey, byte[] publisherMlDsaPubKey,
			String name, String description, @Nullable byte[] avatarHash,
			long createdAtHourMs, boolean publicChannel,
			@Nullable byte[] joinCapability, String currentOnion,
			long manifestSeq, boolean weArePublisher,
			long highestKnownPostSeq,
			@Nullable byte[] contentKeyHash,
			@Nullable byte[] contentKey,
			List<ChannelDelegationCert> activeDelegations,
			List<Long> revokedDelegationSeqs,
			long nextDelegationSeq) {
		this(channelId, salt, publisherEd25519PubKey, publisherMlDsaPubKey,
				name, description, avatarHash, createdAtHourMs,
				publicChannel, joinCapability, currentOnion, manifestSeq,
				weArePublisher, highestKnownPostSeq, contentKeyHash,
				contentKey, activeDelegations, revokedDelegationSeqs,
				nextDelegationSeq, null, NO_PINNED_POST, false);
	}

	public ChannelState(byte[] channelId, byte[] salt,
			byte[] publisherEd25519PubKey, byte[] publisherMlDsaPubKey,
			String name, String description, @Nullable byte[] avatarHash,
			long createdAtHourMs, boolean publicChannel,
			@Nullable byte[] joinCapability, String currentOnion,
			long manifestSeq, boolean weArePublisher,
			long highestKnownPostSeq,
			@Nullable byte[] contentKeyHash,
			@Nullable byte[] contentKey,
			List<ChannelDelegationCert> activeDelegations,
			List<Long> revokedDelegationSeqs,
			long nextDelegationSeq,
			@Nullable String onionPrivateKey) {
		this(channelId, salt, publisherEd25519PubKey, publisherMlDsaPubKey,
				name, description, avatarHash, createdAtHourMs,
				publicChannel, joinCapability, currentOnion, manifestSeq,
				weArePublisher, highestKnownPostSeq, contentKeyHash,
				contentKey, activeDelegations, revokedDelegationSeqs,
				nextDelegationSeq, onionPrivateKey, NO_PINNED_POST,
				false);
	}

	public ChannelState(byte[] channelId, byte[] salt,
			byte[] publisherEd25519PubKey, byte[] publisherMlDsaPubKey,
			String name, String description, @Nullable byte[] avatarHash,
			long createdAtHourMs, boolean publicChannel,
			@Nullable byte[] joinCapability, String currentOnion,
			long manifestSeq, boolean weArePublisher,
			long highestKnownPostSeq,
			@Nullable byte[] contentKeyHash,
			@Nullable byte[] contentKey,
			List<ChannelDelegationCert> activeDelegations,
			List<Long> revokedDelegationSeqs,
			long nextDelegationSeq,
			@Nullable String onionPrivateKey,
			long pinnedPostSeq) {
		this(channelId, salt, publisherEd25519PubKey, publisherMlDsaPubKey,
				name, description, avatarHash, createdAtHourMs,
				publicChannel, joinCapability, currentOnion, manifestSeq,
				weArePublisher, highestKnownPostSeq, contentKeyHash,
				contentKey, activeDelegations, revokedDelegationSeqs,
				nextDelegationSeq, onionPrivateKey, pinnedPostSeq, false);
	}

	public ChannelState(byte[] channelId, byte[] salt,
			byte[] publisherEd25519PubKey, byte[] publisherMlDsaPubKey,
			String name, String description, @Nullable byte[] avatarHash,
			long createdAtHourMs, boolean publicChannel,
			@Nullable byte[] joinCapability, String currentOnion,
			long manifestSeq, boolean weArePublisher,
			long highestKnownPostSeq,
			@Nullable byte[] contentKeyHash,
			@Nullable byte[] contentKey,
			List<ChannelDelegationCert> activeDelegations,
			List<Long> revokedDelegationSeqs,
			long nextDelegationSeq,
			@Nullable String onionPrivateKey,
			long pinnedPostSeq,
			boolean requiresApproval) {
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
		this.contentKeyHash = contentKeyHash;
		this.contentKey = contentKey;
		this.activeDelegations =
				Collections.unmodifiableList(activeDelegations);
		this.revokedDelegationSeqs =
				Collections.unmodifiableList(revokedDelegationSeqs);
		this.nextDelegationSeq = nextDelegationSeq;
		this.onionPrivateKey = onionPrivateKey;
		this.pinnedPostSeq = pinnedPostSeq;
		this.requiresApproval = requiresApproval;
	}

	public long getPinnedPostSeq() {
		return pinnedPostSeq;
	}

	public boolean requiresApproval() {
		return requiresApproval;
	}

	@Nullable
	public String getOnionPrivateKey() {
		return onionPrivateKey;
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

	@Nullable
	public byte[] getContentKeyHash() {
		return contentKeyHash;
	}

	@Nullable
	public byte[] getContentKey() {
		return contentKey;
	}

	public List<ChannelDelegationCert> getActiveDelegations() {
		return activeDelegations;
	}

	public List<Long> getRevokedDelegationSeqs() {
		return revokedDelegationSeqs;
	}

	public long getNextDelegationSeq() {
		return nextDelegationSeq;
	}
}
