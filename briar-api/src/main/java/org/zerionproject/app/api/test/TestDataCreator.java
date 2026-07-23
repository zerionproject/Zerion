package org.zerionproject.app.api.test;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface TestDataCreator {

	void createTestData(int numContacts, int numPrivateMsgs,
			int avatarPercent);

	@IoExecutor
	Contact addContact(String name, boolean alias, boolean avatar)
			throws DbException;
}
