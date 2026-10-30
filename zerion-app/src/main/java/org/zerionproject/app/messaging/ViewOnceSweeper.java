package org.zerionproject.app.messaging;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.event.MessagesAckedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.concurrent.Executor;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
class ViewOnceSweeper implements EventListener {

	static final int MAX_ATTEMPTS = 3;

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final MessagingManagerImpl messagingManager;

	ViewOnceSweeper(DatabaseComponent db, Executor dbExecutor,
			MessagingManagerImpl messagingManager) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.messagingManager = messagingManager;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof MessagesAckedEvent) {
			MessagesAckedEvent a = (MessagesAckedEvent) e;
			sweepAsync(a.getContactId(), a.getMessageIds(), 1);
		}
	}

	private void sweepAsync(ContactId c, Collection<MessageId> acked,
			int attempt) {
		dbExecutor.execute(() -> sweep(c, acked, attempt));
	}

	@DatabaseExecutor
	private void sweep(ContactId c, Collection<MessageId> acked,
			int attempt) {
		try {
			Collection<MessageId> delivered = db.transactionWithResult(true,
					txn -> messagingManager.findDeliveredViewOnce(txn, c,
							acked));
			if (delivered.isEmpty()) return;
			db.transaction(false, txn ->
					messagingManager.clearViewOnceMedia(txn, c, delivered));
		} catch (DbException e) {
			if (attempt < MAX_ATTEMPTS) sweepAsync(c, acked, attempt + 1);
		} catch (RuntimeException e) {
		}
	}
}
