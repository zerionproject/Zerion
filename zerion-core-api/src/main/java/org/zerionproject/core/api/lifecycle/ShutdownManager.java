package org.zerionproject.core.api.lifecycle;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ShutdownManager {

	int addShutdownHook(Runnable hook);

	boolean removeShutdownHook(int handle);
}
