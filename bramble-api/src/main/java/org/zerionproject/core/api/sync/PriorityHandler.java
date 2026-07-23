package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface PriorityHandler {

	void handle(Priority p);
}
