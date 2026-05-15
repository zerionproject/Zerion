package org.briarproject.bramble.api.versioning;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ClientVersioningManager {

	ClientId CLIENT_ID = new ClientId("org.briarproject.bramble.versioning");

	int MAJOR_VERSION = 0;

	void registerClient(ClientId clientId, int majorVersion, int minorVersion,
			ClientVersioningHook hook);

	Visibility getClientVisibility(Transaction txn, ContactId contactId,
			ClientId clientId, int majorVersion) throws DbException;

	int getClientMinorVersion(Transaction txn, ContactId contactId,
			ClientId clientId, int majorVersion) throws DbException;

	interface ClientVersioningHook {

		void onClientVisibilityChanging(Transaction txn, Contact c,
				Visibility v) throws DbException;
	}
}
