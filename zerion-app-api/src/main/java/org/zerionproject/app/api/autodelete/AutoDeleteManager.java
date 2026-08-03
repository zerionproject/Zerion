package org.zerionproject.app.api.autodelete;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.ClientId;
import org.briarproject.nullsafety.NotNullByDefault;

import static java.util.concurrent.TimeUnit.DAYS;

@NotNullByDefault
public interface AutoDeleteManager {

	ClientId CLIENT_ID = new ClientId("org.zerionproject.app.autodelete");

	int MAJOR_VERSION = 0;

	int MINOR_VERSION = 0;

	long DEFAULT_TIMER_DURATION = DAYS.toMillis(7);

	long getAutoDeleteTimer(Transaction txn, ContactId c) throws DbException;

	long getAutoDeleteTimer(Transaction txn, ContactId c, long timestamp)
			throws DbException;

	void setAutoDeleteTimer(Transaction txn, ContactId c, long timer)
			throws DbException;

	void receiveAutoDeleteTimer(Transaction txn, ContactId c, long timer,
			long timestamp) throws DbException;
}
