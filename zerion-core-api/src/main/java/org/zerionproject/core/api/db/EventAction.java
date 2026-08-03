package org.zerionproject.core.api.db;

import org.zerionproject.core.api.event.Event;

public class EventAction implements CommitAction {

	private final Event event;

	EventAction(Event event) {
		this.event = event;
	}

	public Event getEvent() {
		return event;
	}

	@Override
	public void accept(Visitor visitor) {
		visitor.visit(this);
	}
}
