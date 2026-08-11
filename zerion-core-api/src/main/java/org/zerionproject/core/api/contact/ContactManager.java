package org.zerionproject.core.api.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.PendingContactExistsException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.Collection;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ContactManager {

	void registerContactHook(ContactHook hook);

	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			SecretKey rootKey, long timestamp, boolean alice, boolean verified,
			boolean active) throws DbException;

	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			SecretKey rootKey, long timestamp, boolean alice, boolean verified,
			boolean active,
			@javax.annotation.Nullable byte[] peerMlDsaSigPublicKey)
			throws DbException;

	ContactId addContact(Transaction txn, PendingContactId p, Author remote,
			AuthorId local, SecretKey rootKey, long timestamp, boolean alice,
			boolean verified, boolean active)
			throws DbException, GeneralSecurityException;

	ContactId addContact(Transaction txn, PendingContactId p, Author remote,
			AuthorId local, SecretKey rootKey, long timestamp, boolean alice,
			boolean verified, boolean active,
			@javax.annotation.Nullable byte[] peerMlDsaSigPublicKey)
			throws DbException, GeneralSecurityException;

	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			boolean verified) throws DbException;

	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			SecretKey rootKey, boolean verified,
			@javax.annotation.Nullable byte[] peerMlDsaSigPublicKey)
			throws DbException;

	ContactId addContact(Author remote, AuthorId local, SecretKey rootKey,
			long timestamp, boolean alice, boolean verified, boolean active)
			throws DbException;

	@Deprecated
	String getHandshakeLink() throws DbException;

	@Deprecated
	String getHandshakeLink(Transaction txn) throws DbException;

	String getHandshakeLink(ContactType contactType) throws DbException;

	String getHandshakeLink(Transaction txn, ContactType contactType)
			throws DbException;

	PendingContact addPendingContact(Transaction txn, String link, String alias)
			throws DbException, FormatException, GeneralSecurityException,
			ContactExistsException, PendingContactExistsException;

	PendingContact addPendingContact(String link, String alias)
			throws DbException, FormatException, GeneralSecurityException,
			ContactExistsException, PendingContactExistsException;

	PendingContact getPendingContact(Transaction txn, PendingContactId p)
			throws DbException;

	Collection<Pair<PendingContact, PendingContactState>> getPendingContacts()
			throws DbException;

	Collection<Pair<PendingContact, PendingContactState>> getPendingContacts(Transaction txn)
			throws DbException;

	void removePendingContact(PendingContactId p) throws DbException;

	void removePendingContact(Transaction txn, PendingContactId p)
			throws DbException;

	Contact getContact(ContactId c) throws DbException;

	Contact getContact(Transaction txn, ContactId c) throws DbException;

	Contact getContact(AuthorId remoteAuthorId, AuthorId localAuthorId)
			throws DbException;

	Contact getContact(Transaction txn, AuthorId remoteAuthorId,
			AuthorId localAuthorId) throws DbException;

	Collection<Contact> getContacts() throws DbException;

	Collection<Contact> getContacts(Transaction txn) throws DbException;

	void removeContact(ContactId c) throws DbException;

	void removeContact(Transaction txn, ContactId c) throws DbException;

	void setContactAlias(Transaction txn, ContactId c, @Nullable String alias)
			throws DbException;

	void setContactAlias(ContactId c, @Nullable String alias)
			throws DbException;

	boolean contactExists(Transaction txn, AuthorId remoteAuthorId,
			AuthorId localAuthorId) throws DbException;

	boolean contactExists(AuthorId remoteAuthorId, AuthorId localAuthorId)
			throws DbException;

	interface ContactHook {

		void addingContact(Transaction txn, Contact c) throws DbException;

		void removingContact(Transaction txn, Contact c) throws DbException;
	}
}
