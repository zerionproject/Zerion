package org.briarproject.bramble.api.event;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface EventBus {

	void addListener(EventListener l);

	void removeListener(EventListener l);

	void broadcast(Event e);
}
