package org.briarproject.bramble.api.db;

import org.briarproject.bramble.api.contact.PendingContact;


public class PendingContactExistsException extends DbException {

	private final PendingContact pendingContact;

	public PendingContactExistsException(PendingContact pendingContact) {
		this.pendingContact = pendingContact;
	}

	public PendingContact getPendingContact() {
		return pendingContact;
	}
}
