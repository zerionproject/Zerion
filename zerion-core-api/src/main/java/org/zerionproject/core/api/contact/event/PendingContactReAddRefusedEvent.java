package org.zerionproject.core.api.contact.event;

import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.identity.Author;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PendingContactReAddRefusedEvent extends Event {

	private final PendingContactId id;
	private final Author author;
	@Nullable
	private final String alias;

	public PendingContactReAddRefusedEvent(PendingContactId id, Author author,
			@Nullable String alias) {
		this.id = id;
		this.author = author;
		this.alias = alias;
	}

	public PendingContactId getId() {
		return id;
	}

	public Author getAuthor() {
		return author;
	}

	@Nullable
	public String getAlias() {
		return alias;
	}
}
