package org.zerionproject.core.api.connection;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ContactConnectionDiagnostics {

	ContactConnectionSnapshot getSnapshot(ContactId c) throws DbException;
}
