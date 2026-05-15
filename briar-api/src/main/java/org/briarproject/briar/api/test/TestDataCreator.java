package org.briarproject.briar.api.test;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface TestDataCreator {

	void createTestData(int numContacts, int numPrivateMsgs, int avatarPercent,
			int numPrivateGroups, int numPrivateGroupMessages);

	@IoExecutor
	Contact addContact(String name, boolean alias, boolean avatar)
			throws DbException;
}
