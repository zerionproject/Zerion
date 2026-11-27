package org.zerionproject.app.messaging;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.versioning.ClientVersion;
import org.zerionproject.core.api.versioning.ClientVersioningManager.RemoteVersionHook;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class UnsignedFormsMemory implements RemoteVersionHook,
		ContactManager.ContactHook, OpenDatabaseHook {

	public enum Form {
		UNSIGNED, SIGNED, UNKNOWN
	}

	public static final String NAMESPACE = "org.zerionproject.mesh/linkKeys";
	public static final String UNSIGNED_ONLY = "unsignedOnly.";
	public static final int MIN_VERSION =
			MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION;

	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final ContactManager contactManager;
	private final MessagingManager messagingManager;

	public UnsignedFormsMemory(DatabaseComponent db,
			SettingsManager settingsManager, ContactManager contactManager,
			MessagingManager messagingManager) {
		this.db = db;
		this.settingsManager = settingsManager;
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
	}

	public Form form(Transaction txn, ContactId c) throws DbException {
		if (held(txn, c)) return Form.UNSIGNED;
		int minor = messagingManager.getContactClientMinorVersion(txn, c);
		if (minor >= MIN_VERSION) return Form.UNSIGNED;
		if (minor < 0) return Form.UNKNOWN;
		return Form.SIGNED;
	}

	public Form form(ContactId c) throws DbException {
		return db.transactionWithResult(true, txn -> form(txn, c));
	}

	@Override
	public void remoteVersionStored(Transaction txn, ContactId c,
			ClientVersion v) throws DbException {
		if (!v.getClientId().equals(MessagingManager.CLIENT_ID)) return;
		if (v.getMajorVersion() != MessagingManager.MAJOR_VERSION) return;
		if (v.getMinorVersion() < MIN_VERSION) return;
		Settings update = new Settings();
		update.putBoolean(UNSIGNED_ONLY + c.getInt(), true);
		settingsManager.mergeSettings(txn, update, NAMESPACE);
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		forget(txn, c.getId());
	}

	@Override
	public void removingContact(Transaction txn, Contact c)
			throws DbException {
		forget(txn, c.getId());
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Settings held = settingsManager.getSettings(txn, NAMESPACE);
		Settings update = new Settings();
		for (Contact contact : contactManager.getContacts(txn)) {
			ContactId c = contact.getId();
			String name = UNSIGNED_ONLY + c.getInt();
			if (held.getBoolean(name, false)) continue;
			try {
				if (messagingManager.getContactClientMinorVersion(txn, c)
						>= MIN_VERSION) {
					update.putBoolean(name, true);
				}
			} catch (DbException e) {
			}
		}
		if (!update.isEmpty()) {
			settingsManager.mergeSettings(txn, update, NAMESPACE);
		}
	}

	private boolean held(Transaction txn, ContactId c) throws DbException {
		return settingsManager.getSettings(txn, NAMESPACE)
				.getBoolean(UNSIGNED_ONLY + c.getInt(), false);
	}

	private void forget(Transaction txn, ContactId c) throws DbException {
		Settings update = new Settings();
		update.put(UNSIGNED_ONLY + c.getInt(), "");
		settingsManager.mergeSettings(txn, update, NAMESPACE);
	}
}
