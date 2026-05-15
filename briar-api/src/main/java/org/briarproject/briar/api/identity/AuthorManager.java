package org.briarproject.briar.api.identity;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface AuthorManager {

	AuthorInfo getAuthorInfo(AuthorId a) throws DbException;

	AuthorInfo getAuthorInfo(Transaction txn, AuthorId a) throws DbException;

	AuthorInfo getAuthorInfo(Contact c) throws DbException;

	AuthorInfo getAuthorInfo(Transaction txn, Contact c)
			throws DbException;

	AuthorInfo getMyAuthorInfo() throws DbException;

	AuthorInfo getMyAuthorInfo(Transaction txn) throws DbException;
}
