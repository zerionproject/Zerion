package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ChannelSubscriber {

	private final String displayName;
	private final byte[] ed25519PubKey;
	private final byte[] mlDsaPubKey;
	private final long joinedAtHourMs;
	private final boolean banned;

	public ChannelSubscriber(String displayName, byte[] ed25519PubKey,
			byte[] mlDsaPubKey, long joinedAtHourMs, boolean banned) {
		this.displayName = displayName;
		this.ed25519PubKey = ed25519PubKey;
		this.mlDsaPubKey = mlDsaPubKey;
		this.joinedAtHourMs = joinedAtHourMs;
		this.banned = banned;
	}

	public String getDisplayName() {
		return displayName;
	}

	public byte[] getEd25519PubKey() {
		return ed25519PubKey;
	}

	public byte[] getMlDsaPubKey() {
		return mlDsaPubKey;
	}

	public long getJoinedAtHourMs() {
		return joinedAtHourMs;
	}

	public boolean isBanned() {
		return banned;
	}
}
