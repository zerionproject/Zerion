package org.briarproject.bramble.api.sync.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.Group.Visibility;
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
