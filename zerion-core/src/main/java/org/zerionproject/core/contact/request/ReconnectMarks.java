package org.zerionproject.core.contact.request;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Remembers, per request id, that a request asks to reconnect with someone
 * who is already a contact: on the sender's side because the user started it
 * from Re-add contact, on the receiver's side because the request said so.
 */
@NotNullByDefault
final class ReconnectMarks {

	static final String OUTGOING =
			"org.zerionproject.contact.request.reconnect.outgoing";
	static final String INCOMING =
			"org.zerionproject.contact.request.reconnect.incoming";

	private final DatabaseComponent db;

	ReconnectMarks(DatabaseComponent db) {
		this.db = db;
	}

	void put(Transaction txn, byte[] requestId, String namespace)
			throws DbException {
		Settings s = new Settings();
		s.putBoolean(StringUtils.toHexString(requestId), true);
		db.mergeSettings(txn, s, namespace);
	}

	boolean get(Transaction txn, byte[] requestId, String namespace)
			throws DbException {
		return db.getSettings(txn, namespace)
				.getBoolean(StringUtils.toHexString(requestId), false);
	}

	void clear(Transaction txn, byte[] requestId, String namespace)
			throws DbException {
		if (!get(txn, requestId, namespace)) return;
		Settings s = new Settings();
		s.putBoolean(StringUtils.toHexString(requestId), false);
		db.mergeSettings(txn, s, namespace);
	}
}
