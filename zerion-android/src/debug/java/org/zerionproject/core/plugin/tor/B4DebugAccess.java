package org.zerionproject.core.plugin.tor;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DbException;

@NotNullByDefault
public final class B4DebugAccess {

	private B4DebugAccess() {
	}

	public static void evaluateNow(B4OnionRotation rotation) {
		rotation.evaluateAll();
	}

	public static boolean forceComplete(B4OnionRotation rotation)
			throws DbException {
		return rotation.forceCompleteRotation();
	}
}
