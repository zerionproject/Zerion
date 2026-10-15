package com.professor.zerion.android.navdrawer;

import org.zerionproject.core.api.plugin.Plugin;

final class TorStatusDecider {

	enum Status {
		OFFLINE_MODE,
		DISABLED,
		CONNECTED,
		CLOCK_SKEW,
		ENTRY_RELAY_FAILING,
		PUBLISHING,
		BOOTSTRAPPING,
		CONNECTING
	}

	private TorStatusDecider() {
	}

	static Status decide(boolean offlineMode, Plugin.State state,
			boolean online, boolean onionPublished, boolean clockSkewed,
			int bootstrapPercent) {
		return decide(offlineMode, state, online, onionPublished, clockSkewed,
				false, bootstrapPercent);
	}

	static Status decide(boolean offlineMode, Plugin.State state,
			boolean online, boolean onionPublished, boolean clockSkewed,
			boolean entryRelayFailing, int bootstrapPercent) {
		if (offlineMode) return Status.OFFLINE_MODE;
		if (state == null || state == Plugin.State.DISABLED) {
			return Status.DISABLED;
		}
		if (clockSkewed) return Status.CLOCK_SKEW;
		if (state == Plugin.State.ACTIVE && online && entryRelayFailing) {
			return Status.ENTRY_RELAY_FAILING;
		}
		if (state == Plugin.State.ACTIVE && online) {
			if (onionPublished) return Status.CONNECTED;
			return Status.PUBLISHING;
		}
		if (bootstrapPercent > 0 && bootstrapPercent < 100) {
			return Status.BOOTSTRAPPING;
		}
		return Status.CONNECTING;
	}
}
