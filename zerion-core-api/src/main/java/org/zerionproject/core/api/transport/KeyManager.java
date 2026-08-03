package org.zerionproject.core.api.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.TransportId;

import java.security.GeneralSecurityException;
import java.util.Map;

import javax.annotation.Nullable;

public interface KeyManager {

	@Nullable
	KeySetId addRotationKeys(Transaction txn, ContactId c, TransportId t,
			SecretKey rootKey, long timestamp, boolean alice,
			boolean active) throws DbException;

	Map<TransportId, KeySetId> addRotationKeys(Transaction txn,
			ContactId c, SecretKey rootKey, long timestamp, boolean alice,
			boolean active) throws DbException;

	Map<TransportId, KeySetId> addContact(Transaction txn, ContactId c,
			PublicKey theirPublicKey, KeyPair ourKeyPair)
			throws DbException, GeneralSecurityException;

	Map<TransportId, KeySetId> addPendingContact(Transaction txn,
			PendingContactId p, PublicKey theirPublicKey, KeyPair ourKeyPair)
			throws DbException, GeneralSecurityException;

	Map<TransportId, KeySetId> addHybridPendingContact(Transaction txn,
			PendingContactId p, SecretKey rendezvousKey, boolean alice)
			throws DbException;

	void activateKeys(Transaction txn, Map<TransportId, KeySetId> keys)
			throws DbException;

	boolean canSendOutgoingStreams(ContactId c, TransportId t);

	boolean canSendOutgoingStreams(PendingContactId p, TransportId t);

	@Nullable
	StreamContext getStreamContext(ContactId c, TransportId t)
			throws DbException;

	@Nullable
	StreamContext getStreamContext(PendingContactId p, TransportId t)
			throws DbException;

	@Nullable
	StreamContext getStreamContext(TransportId t, byte[] tag)
			throws DbException;

	@Nullable
	StreamContext getStreamContextOnly(TransportId t, byte[] tag)
			throws DbException;

	void markTagAsRecognised(TransportId t, byte[] tag) throws DbException;
}
