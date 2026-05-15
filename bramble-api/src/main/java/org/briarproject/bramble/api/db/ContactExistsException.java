package org.briarproject.bramble.api.db;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;

public class ContactExistsException extends DbException {

	private final AuthorId local;
	private final Author remote;

	public ContactExistsException(AuthorId local, Author remote) {
		this.local = local;
		this.remote = remote;
	}

	public AuthorId getLocalAuthorId() {
		return local;
	}

	public Author getRemoteAuthor() {
		return remote;
	}
}
