package com.professor.zerion.android.conversation;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;

import java.util.Collections;

import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_IMAGES;
import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_ONLY;

@NotNullByDefault
public final class OutgoingTextMessages {

	private OutgoingTextMessages() {
	}

	public static PrivateMessage create(Transaction txn,
			MessagingManager messagingManager,
			AutoDeleteManager autoDeleteManager,
			PrivateMessageFactory factory, ContactId contactId,
			GroupId groupId, long timestamp, String text)
			throws DbException, FormatException {
		PrivateMessageFormat format =
				messagingManager.getContactMessageFormat(txn, contactId);
		if (format == TEXT_ONLY) {
			return factory.createLegacyPrivateMessage(groupId, timestamp,
					text);
		}
		if (format == TEXT_IMAGES) {
			return factory.createPrivateMessage(groupId, timestamp, text,
					Collections.emptyList());
		}
		long timer = autoDeleteManager.getAutoDeleteTimer(txn, contactId,
				timestamp);
		return factory.createPrivateMessage(groupId, timestamp, text,
				Collections.emptyList(), timer, null);
	}
}
