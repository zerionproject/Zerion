package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ZppPacing {

	long activeIntervalMs();

	long idleIntervalMs();

	long idleAfterMs();
}
