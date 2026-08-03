package org.zerionproject.core.api.sync.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupVisibilityUpdatedEvent extends Event {

	private final Visibility visibility;
	private final Collection<ContactId> affected;

	public GroupVisibilityUpdatedEvent(Visibility visibility,
			Collection<ContactId> affected) {
		this.visibility = visibility;
		this.affected = affected;
	}

	public Visibility getVisibility() {
		return visibility;
	}

	public Collection<ContactId> getAffectedContacts() {
		return affected;
	}
}
