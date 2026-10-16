package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ChannelSyncStatus {

	public static final long NEVER = -1L;

	public static final ChannelSyncStatus UNKNOWN =
			new ChannelSyncStatus(NEVER, false, false);

	private final long lastUpdatedMs;
	private final boolean lastAttemptFailed;
	private final boolean dormant;

	public ChannelSyncStatus(long lastUpdatedMs, boolean lastAttemptFailed,
			boolean dormant) {
		this.lastUpdatedMs = lastUpdatedMs;
		this.lastAttemptFailed = lastAttemptFailed;
		this.dormant = dormant;
	}

	public long getLastUpdatedMs() {
		return lastUpdatedMs;
	}

	public boolean isLastAttemptFailed() {
		return lastAttemptFailed;
	}

	public boolean isDormant() {
		return dormant;
	}
}
