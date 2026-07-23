package org.zerionproject.core.api.identity.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.identity.AuthorId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class IdentityAddedEvent extends Event {

	private final AuthorId authorId;

	public IdentityAddedEvent(AuthorId authorId) {
		this.authorId = authorId;
	}

	public AuthorId getAuthorId() {
		return authorId;
	}
}
