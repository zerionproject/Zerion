package com.professor.zerion.android.chat;

import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
class ChatItem {

	enum Type {CONTACT, GROUP, CHANNEL}

	private final Type type;
	private final int contactId;
	@Nullable
	private final byte[] blobId;
	private final String name;
	private final long time;
	private final int unread;
	private final boolean pinned;
	private final boolean online;
	@Nullable
	private final AttachmentHeader avatarHeader;

	ChatItem(Type type, int contactId, @Nullable byte[] blobId, String name,
			long time, int unread, boolean pinned, boolean online,
			@Nullable AttachmentHeader avatarHeader) {
		this.type = type;
		this.contactId = contactId;
		this.blobId = blobId;
		this.name = name;
		this.time = time;
		this.unread = unread;
		this.pinned = pinned;
		this.online = online;
		this.avatarHeader = avatarHeader;
	}

	@Nullable
	AttachmentHeader getAvatarHeader() {
		return avatarHeader;
	}

	Type getType() {
		return type;
	}

	int getContactId() {
		return contactId;
	}

	@Nullable
	byte[] getBlobId() {
		return blobId;
	}

	String getName() {
		return name;
	}

	long getTime() {
		return time;
	}

	int getUnread() {
		return unread;
	}

	boolean isPinned() {
		return pinned;
	}

	boolean isOnline() {
		return online;
	}
}
