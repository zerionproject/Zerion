package org.zerionproject.core.api.db;

import org.zerionproject.core.api.contact.PendingContact;

public class PendingContactExistsException extends DbException {

	private final PendingContact pendingContact;

	public PendingContactExistsException(PendingContact pendingContact) {
		this.pendingContact = pendingContact;
	}

	public PendingContact getPendingContact() {
		return pendingContact;
	}
}
