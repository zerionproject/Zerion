package org.zerionproject.core.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.StreamContext;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
interface TransportKeyManager {

	void start(Transaction txn) throws DbException;

	KeySetId addRotationKeys(Transaction txn, ContactId c,
			SecretKey rootKey, long timestamp, boolean alice, boolean active)
			throws DbException;

	KeySetId addHandshakeKeys(Transaction txn, ContactId c,
			SecretKey rootKey, boolean alice) throws DbException;

	KeySetId addHandshakeKeys(Transaction txn, PendingContactId p,
			SecretKey rootKey, boolean alice) throws DbException;

	void activateKeys(Transaction txn, KeySetId k) throws DbException;

	void removeContact(ContactId c);

	void removePendingContact(PendingContactId p);

	boolean canSendOutgoingStreams(ContactId c);

	boolean canSendOutgoingStreams(PendingContactId p);

	@Nullable
	StreamContext getStreamContext(Transaction txn, ContactId c,
			boolean classical) throws DbException;

	@Nullable
	StreamContext getStreamContext(Transaction txn, PendingContactId p,
			boolean classical) throws DbException;

	@Nullable
	StreamContext getStreamContext(Transaction txn, byte[] tag,
			boolean classical) throws DbException;

	@Nullable
	StreamContext getStreamContextOnly(Transaction txn, byte[] tag,
			boolean classical);

	void markTagAsRecognised(Transaction txn, byte[] tag) throws DbException;

}
