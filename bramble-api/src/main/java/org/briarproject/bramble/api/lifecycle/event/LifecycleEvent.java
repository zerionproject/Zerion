package org.briarproject.bramble.api.lifecycle.event;

import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState;

public class LifecycleEvent extends Event {

	private final LifecycleState state;

	public LifecycleEvent(LifecycleState state) {
		this.state = state;
	}

	public LifecycleState getLifecycleState() {
		return state;
	}
}
