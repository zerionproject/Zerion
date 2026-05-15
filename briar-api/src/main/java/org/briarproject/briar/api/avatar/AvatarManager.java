package org.briarproject.briar.api.avatar;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public interface AvatarManager {

	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.avatar");

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
