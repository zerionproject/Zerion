package org.zerionproject.core.plugin.tor.auth;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class OnionAuthDebugAccess {

	private OnionAuthDebugAccess() {
	}

	public static void checkNow(OnionClientAuthManagerImpl manager) {
		manager.tickForTest();
	}
}
