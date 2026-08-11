package org.zerionproject.app.api.identity;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.LocalAuthor;
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
