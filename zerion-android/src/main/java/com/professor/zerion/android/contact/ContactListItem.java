package com.professor.zerion.android.contact;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.client.MessageTracker.GroupCount;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactListItem extends ContactItem
		implements Comparable<ContactListItem> {

	private final boolean empty;
	private final long timestamp;
	private final int unread;
	private final boolean pinned;

	public ContactListItem(Contact contact, AuthorInfo authorInfo,
			boolean connected, GroupCount count, boolean pinned) {
		super(contact, authorInfo, connected);
		this.empty = count.getMsgCount() == 0;
		this.unread = count.getUnreadCount();
		this.timestamp = count.getLatestMsgTime();
		this.pinned = pinned;
	}

	private ContactListItem(Contact contact, AuthorInfo authorInfo,
			boolean connected, boolean empty, int unread, long timestamp,
			boolean pinned) {
		super(contact, authorInfo, connected);
		this.empty = empty;
		this.timestamp = timestamp;
		this.unread = unread;
		this.pinned = pinned;
	}

	ContactListItem(ContactListItem item, boolean connected) {
		this(item.getContact(), item.getAuthorInfo(), connected, item.empty,
				item.unread, item.timestamp, item.pinned);
	}

	ContactListItem(ContactListItem item, long timestamp, boolean read) {
		this(item.getContact(), item.getAuthorInfo(), item.isConnected(), false,
				read ? item.unread : item.unread + 1,
				Math.max(timestamp, item.timestamp), item.pinned);
	}

	ContactListItem(ContactListItem item, @Nullable String alias) {
		this(update(item.getContact(), alias), item.getAuthorInfo(),
				item.isConnected(), item.empty, item.unread, item.timestamp,
				item.pinned);
	}

	private static Contact update(Contact c, @Nullable String alias) {
		return new Contact(c.getId(), c.getAuthor(), c.getLocalAuthorId(),
				alias, c.getHandshakePublicKey(), c.isVerified());
	}

	ContactListItem(ContactListItem item, AttachmentHeader attachmentHeader) {
		this(item.getContact(), new AuthorInfo(item.getAuthorInfo().getStatus(),
						item.getAuthorInfo().getAlias(), attachmentHeader),
				item.isConnected(), item.empty, item.unread, item.timestamp,
				item.pinned);
	}

	ContactListItem(ContactListItem item, GroupCount count) {
		this(item.getContact(), item.getAuthorInfo(), item.isConnected(),
				count.getMsgCount() == 0, count.getUnreadCount(),
				count.getLatestMsgTime(), item.pinned);
	}

	ContactListItem(ContactListItem item, boolean pinned, int ignored) {
		this(item.getContact(), item.getAuthorInfo(), item.isConnected(),
				item.empty, item.unread, item.timestamp, pinned);
	}

	boolean isEmpty() {
		return empty;
	}

	long getTimestamp() {
		return timestamp;
	}

	int getUnreadCount() {
		return unread;
	}

	boolean isPinned() {
		return pinned;
	}

	@Override
	public int compareTo(ContactListItem o) {
		if (this.pinned != o.pinned) return this.pinned ? -1 : 1;
		return Long.compare(o.getTimestamp(), timestamp);
	}
}
