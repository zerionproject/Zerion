package org.zerionproject.core.api.sync.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.Group;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupRemovedEvent extends Event {

	private final Group group;

	public GroupRemovedEvent(Group group) {
		this.group = group;
	}

	public Group getGroup() {
		return group;
	}
}
