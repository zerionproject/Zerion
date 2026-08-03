package org.zerionproject.app.api.attachment;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;

public interface AttachmentReader {

	Attachment getAttachment(AttachmentHeader h) throws DbException;

	Attachment getAttachment(Transaction txn, AttachmentHeader h)
			throws DbException;

}
