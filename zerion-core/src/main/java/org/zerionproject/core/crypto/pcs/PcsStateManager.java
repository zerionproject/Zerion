package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.lifecycle.ServiceException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_RECEIVE;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_SEND;

@ThreadSafe
@Singleton
@NotNullByDefault
public class PcsStateManager implements Service {
	private final DatabaseComponent db;

	@Inject
	public PcsStateManager(DatabaseComponent db,
			LifecycleManager lifecycleManager) {
		this.db = db;
		lifecycleManager.registerService(this);
	}

	@Override
	public void startService() throws ServiceException {
	}

	@Override
	public void stopService() throws ServiceException {
	}

	@Nullable
	public PcsSessionState loadSendState(ContactId contactId) {
		try {
			return db.transactionWithNullableResult(true, txn ->
					loadSendState(txn, contactId));
		} catch (DbException e) {
			return null;
		}
	}

	@Nullable
	public PcsSessionState loadSendState(Transaction txn, ContactId contactId)
			throws DbException {
		Object[] row = db.getPcsMode2SessionState(txn, contactId,
				PCS_DIRECTION_SEND);
		if (row == null) return null;
		byte[] rootKeyBytes = (byte[]) row[3];
		if (!(Boolean) row[7] || rootKeyBytes == null) return null;
		SecretKey rootKey = new SecretKey(rootKeyBytes);
		return new PcsSessionState(new SecretKey((byte[]) row[0]),
				(Integer) row[1], (Integer) row[2], rootKey, null);
	}

	public void stripDeadState(ContactId contactId) throws DbException {
		db.transaction(false, txn -> {
			stripDeadState(txn, contactId, PCS_DIRECTION_SEND);
			stripDeadState(txn, contactId, PCS_DIRECTION_RECEIVE);
			if (db.containsPqRatchetState(txn, contactId)) {
				db.removePqRatchetState(txn, contactId);
			}
		});
	}

	private void stripDeadState(Transaction txn, ContactId contactId,
			int direction) throws DbException {
		Object[] row = db.getPcsMode2SessionState(txn, contactId, direction);
		if (row == null) return;
		byte[] dhPrivate = (byte[]) row[4];
		byte[] blob = (byte[]) row[8];
		if (dhPrivate == null && row[5] == null && row[6] == null
				&& blob == null) {
			return;
		}
		if (dhPrivate != null) Arrays.fill(dhPrivate, (byte) 0);
		if (blob != null) Arrays.fill(blob, (byte) 0);
		byte[] rootKeyBytes = (byte[]) row[3];
		db.setPcsMode2SessionState(txn, contactId, direction,
				new SecretKey((byte[]) row[0]), (Integer) row[1],
				(Integer) row[2],
				rootKeyBytes == null ? null : new SecretKey(rootKeyBytes),
				null, null, null, (Boolean) row[7], null);
	}

	public void initializePairingRoot(Transaction txn, ContactId contactId,
			SecretKey rootKey) throws DbException {
		for (int direction : new int[] {PCS_DIRECTION_SEND,
				PCS_DIRECTION_RECEIVE}) {
			db.setPcsMode2SessionState(txn, contactId, direction, rootKey, 0,
					0, rootKey, null, null, null, true, null);
		}
	}

	public boolean hasState(Transaction txn, ContactId contactId)
			throws DbException {
		return db.containsPcsSessionState(txn, contactId);
	}

	public void removeState(ContactId contactId) {
		try {
			db.transaction(false, txn ->
					db.removePcsState(txn, contactId));
		} catch (DbException e) {
		}
	}
}
