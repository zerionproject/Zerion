package org.zerionproject.crypto;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager.ContactHook;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.wire.ZwfStreamCounter;

@NotNullByDefault
public class StreamCounterContactHook implements ContactHook {

	private final ZwfStreamCounter counter;
	private final SettingsStreamCounterStore store;

	public StreamCounterContactHook(ZwfStreamCounter counter,
			SettingsStreamCounterStore store) {
		this.counter = counter;
		this.store = store;
	}

	@Override
	public void addingContact(Transaction txn, Contact c) {
	}

	@Override
	public void removingContact(Transaction txn, Contact c)
			throws DbException {
		int contactId = c.getId().getInt();
		counter.retireContact(contactId);
		store.clearHighWater(txn, contactId);
	}
}
