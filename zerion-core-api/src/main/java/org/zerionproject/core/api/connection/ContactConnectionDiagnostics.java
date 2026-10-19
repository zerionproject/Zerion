package org.zerionproject.core.api.connection;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.List;

@NotNullByDefault
public interface ContactConnectionDiagnostics {

	ContactConnectionSnapshot getSnapshot(ContactId c) throws DbException;

	default List<ContactId> getContactsHoldingAddressChange()
			throws DbException {
		return Collections.emptyList();
	}

	default long getLastConnectedAgoMs(ContactId c) throws DbException {
		return ContactConnectionSnapshot.UNKNOWN;
	}
}
