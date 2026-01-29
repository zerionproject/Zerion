package org.briarproject.bramble.api.event;

import org.briarproject.nullsafety.NotNullByDefault;


@NotNullByDefault
public interface EventListener {

	
	@EventExecutor
	void eventOccurred(Event e);
}
