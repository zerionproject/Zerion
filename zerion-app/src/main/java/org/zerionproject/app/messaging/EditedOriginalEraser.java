package org.zerionproject.app.messaging;

import org.zerionproject.app.api.messaging.event.PrivateMessageReceivedEvent;
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

import static java.util.Collections.singletonList;

@ThreadSafe
@NotNullByDefault
class EditedOriginalEraser implements EventListener {

	static final int MAX_ATTEMPTS = 3;

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final MessagingManagerImpl messagingManager;

	EditedOriginalEraser(DatabaseComponent db, Executor dbExecutor,
			MessagingManagerImpl messagingManager) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.messagingManager = messagingManager;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof MessagesAckedEvent) {
			MessagesAckedEvent a = (MessagesAckedEvent) e;
			eraseAsync(a.getContactId(), a.getMessageIds());
		} else if (e instanceof PrivateMessageReceivedEvent) {
			PrivateMessageReceivedEvent p = (PrivateMessageReceivedEvent) e;
			if (p.getMessageHeader().isEdited()) {
				eraseAsync(p.getContactId(),
						singletonList(p.getMessageHeader().getId()));
			}
		}
	}

	private void eraseAsync(ContactId c, Collection<MessageId> candidates) {
		eraseAsync(c, candidates, 1);
	}

	private void eraseAsync(ContactId c, Collection<MessageId> candidates,
			int attempt) {
		dbExecutor.execute(() -> erase(c, candidates, attempt));
	}

	@DatabaseExecutor
	private void erase(ContactId c, Collection<MessageId> candidates,
			int attempt) {
		try {
			Collection<MessageId> found = db.transactionWithResult(true,
					txn -> messagingManager.findKeptOriginals(txn, c,
							candidates));
			if (found.isEmpty()) return;
			db.transaction(false, txn ->
					messagingManager.eraseKeptOriginals(txn, c, found));
		} catch (DbException e) {
			if (attempt < MAX_ATTEMPTS) eraseAsync(c, candidates, attempt + 1);
		} catch (RuntimeException e) {
		}
	}
}
