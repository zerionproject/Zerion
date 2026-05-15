package org.briarproject.bramble.api.sync.validation;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;

public interface IncomingMessageHook {

	DeliveryAction incomingMessage(Transaction txn, Message m, Metadata meta)
			throws DbException, InvalidMessageException;

	enum DeliveryAction {

		REJECT,

		DEFER,

		ACCEPT_SHARE,

		ACCEPT_DO_NOT_SHARE
	}
}
