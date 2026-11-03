package org.zerionproject.core.contact;

import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Marks that belong to one pending contact for the time it exists: that its
 * code was scanned in person, and that the user started the add as a re-add
 * of a contact they already have. A mark is keyed on the pending contact's id
 * and its creation time, so a pending contact created later under the same
 * id (the id is derived from the link) never inherits it, and it is consumed
 * when the pending contact becomes a contact or is removed.
 */
@NotNullByDefault
public class PendingContactFlags {

	public static final String IN_PERSON =
			"org.zerionproject.contact.scannedInPerson";
	public static final String RE_ADD =
			"org.zerionproject.contact.reAddExisting";

	private final DatabaseComponent db;

	public PendingContactFlags(DatabaseComponent db) {
		this.db = db;
	}

	static String key(PendingContact p) {
		return StringUtils.toHexString(p.getId().getBytes()) + "."
				+ Long.toHexString(p.getTimestamp());
	}

	public void put(Transaction txn, PendingContact p, String flag)
			throws DbException {
		Settings s = new Settings();
		s.putBoolean(key(p), true);
		db.mergeSettings(txn, s, flag);
	}

	public boolean peek(Transaction txn, PendingContact p, String flag)
			throws DbException {
		return db.getSettings(txn, flag).getBoolean(key(p), false);
	}

	public boolean take(Transaction txn, PendingContact p, String flag)
			throws DbException {
		if (!peek(txn, p, flag)) return false;
		Settings clear = new Settings();
		clear.putBoolean(key(p), false);
		db.mergeSettings(txn, clear, flag);
		return true;
	}

	public void clear(Transaction txn, PendingContact p) throws DbException {
		take(txn, p, IN_PERSON);
		take(txn, p, RE_ADD);
	}
}
