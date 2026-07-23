package org.zerionproject.core.api.sync.validation;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;

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
