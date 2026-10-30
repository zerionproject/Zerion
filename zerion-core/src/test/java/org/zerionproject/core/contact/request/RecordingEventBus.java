package org.zerionproject.core.contact.request;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordingEventBus implements EventBus {

	public final List<Event> events = new CopyOnWriteArrayList<>();
	private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

	@Override
	public void addListener(EventListener l) {
		listeners.add(l);
	}

	@Override
	public void removeListener(EventListener l) {
		listeners.remove(l);
	}

	@Override
	public void broadcast(Event e) {
		events.add(e);
		for (EventListener l : listeners) l.eventOccurred(e);
	}

	public <T extends Event> List<T> of(Class<T> type) {
		List<T> out = new ArrayList<>();
		for (Event e : events) if (type.isInstance(e)) out.add(type.cast(e));
		return out;
	}
}
