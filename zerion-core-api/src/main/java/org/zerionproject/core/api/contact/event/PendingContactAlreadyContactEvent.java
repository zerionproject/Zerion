package org.zerionproject.core.api.contact.event;

import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PendingContactAlreadyContactEvent extends Event {

	private final PendingContactId id;
	@Nullable
	private final String alias;

	public PendingContactAlreadyContactEvent(PendingContactId id) {
		this(id, null);
	}

	public PendingContactAlreadyContactEvent(PendingContactId id,
			@Nullable String alias) {
		this.id = id;
		this.alias = alias;
	}

	public PendingContactId getId() {
		return id;
	}

	@Nullable
	public String getAlias() {
		return alias;
	}

}
