package org.briarproject.briar.api.autodelete;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.nullsafety.NotNullByDefault;

import static java.util.concurrent.TimeUnit.DAYS;

@NotNullByDefault
public interface AutoDeleteManager {

	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.autodelete");

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
