package org.zerionproject.core.api.event;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface EventListener {

	@EventExecutor
	void eventOccurred(Event e);
}
