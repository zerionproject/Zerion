package org.zerionproject.core.api.lifecycle.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState;

public class LifecycleEvent extends Event {

	private final LifecycleState state;

	public LifecycleEvent(LifecycleState state) {
		this.state = state;
	}

	public LifecycleState getLifecycleState() {
		return state;
	}
}
