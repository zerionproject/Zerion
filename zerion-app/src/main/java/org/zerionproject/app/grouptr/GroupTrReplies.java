package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.app.api.messaging.MessagingManager.GROUP_ACTIONS_MIN_VERSION;

@Immutable
@NotNullByDefault
final class GroupTrReplies {

	interface Contacts {

		@Nullable
		Contact find(Transaction txn, byte[] pubKey) throws DbException;
	}

	private final MessagingManager messagingManager;

	GroupTrReplies(MessagingManager messagingManager) {
		this.messagingManager = messagingManager;
	}

	byte[] body(Transaction txn, GroupTrState s, byte[] localPub,
			byte[] targetPostId, String text, Contacts contacts)
			throws DbException {
		if (targetPostId.length == GroupTrBody.REPLY_TARGET_LENGTH
				&& everyRecipientReadsReplies(txn, s, localPub, contacts)) {
			return GroupTrBody.encodeReply(targetPostId, text);
		}
		return GroupTrBody.encodeText(text);
	}

	boolean everyRecipientReadsReplies(Transaction txn, GroupTrState s,
			byte[] localPub, Contacts contacts) throws DbException {
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), localPub)) continue;
			Contact c = contacts.find(txn, m.getPubKey());
			if (c == null) continue;
			if (messagingManager.getContactClientMinorVersion(txn, c.getId())
					< GROUP_ACTIONS_MIN_VERSION) {
				return false;
			}
		}
		return true;
	}
}
