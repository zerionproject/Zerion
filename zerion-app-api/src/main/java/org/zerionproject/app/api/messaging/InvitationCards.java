package org.zerionproject.app.api.messaging;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface InvitationCards {

	boolean stillOpen(Transaction txn, int messageType, byte[] groupTrGroupId)
			throws DbException;
}
