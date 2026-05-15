package org.briarproject.briar.api.attachment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;

public interface AttachmentReader {

	Attachment getAttachment(AttachmentHeader h) throws DbException;

	Attachment getAttachment(Transaction txn, AttachmentHeader h)
			throws DbException;

}
