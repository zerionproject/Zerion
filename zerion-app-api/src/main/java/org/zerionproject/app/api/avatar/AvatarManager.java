package org.zerionproject.app.api.avatar;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public interface AvatarManager {

	ClientId CLIENT_ID = new ClientId("org.zerionproject.app.avatar");

	int MAJOR_VERSION = 0;

	int MINOR_VERSION = 0;

	AttachmentHeader addAvatar(String contentType, InputStream in)
			throws DbException, IOException;

	@Nullable
	AttachmentHeader getAvatarHeader(Transaction txn, Contact c)
			throws DbException;

	@Nullable
	AttachmentHeader getMyAvatarHeader(Transaction txn) throws DbException;
}
