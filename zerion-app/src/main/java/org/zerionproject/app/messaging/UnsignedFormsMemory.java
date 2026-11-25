package org.zerionproject.app.messaging;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.versioning.ClientVersion;
import org.zerionproject.core.api.versioning.event.ClientVersionUpdatedEvent;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class UnsignedFormsMemory implements EventListener,
		ContactManager.ContactHook, OpenDatabaseHook {

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

	public boolean unsignedOnly(Transaction txn, ContactId c)
			throws DbException {
		return held(txn, c) || announced(txn, c);
	}

	public boolean unsignedOnly(ContactId c) throws DbException {
		boolean[] held = new boolean[1];
		boolean unsigned = db.transactionWithResult(true, txn -> {
			held[0] = held(txn, c);
			return held[0] || announced(txn, c);
		});
		if (unsigned && !held[0]) remember(c);
		return unsigned;
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
				if (announced(txn, c)) update.putBoolean(name, true);
			} catch (DbException e) {
			}
		}
		if (!update.isEmpty()) {
			settingsManager.mergeSettings(txn, update, NAMESPACE);
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (!(e instanceof ClientVersionUpdatedEvent)) return;
		ClientVersionUpdatedEvent v = (ClientVersionUpdatedEvent) e;
		ClientVersion version = v.getClientVersion();
		if (!version.getClientId().equals(MessagingManager.CLIENT_ID)) return;
		if (version.getMajorVersion() != MessagingManager.MAJOR_VERSION) {
			return;
		}
		if (version.getMinorVersion() < MIN_VERSION) return;
		try {
			remember(v.getContactId());
		} catch (DbException | RuntimeException ex) {
		}
	}

	private boolean held(Transaction txn, ContactId c) throws DbException {
		return settingsManager.getSettings(txn, NAMESPACE)
				.getBoolean(UNSIGNED_ONLY + c.getInt(), false);
	}

	private boolean announced(Transaction txn, ContactId c)
			throws DbException {
		return messagingManager.getContactClientMinorVersion(txn, c)
				>= MIN_VERSION;
	}

	private void remember(ContactId c) throws DbException {
		db.transaction(false, txn -> {
			contactManager.getContact(txn, c);
			Settings update = new Settings();
			update.putBoolean(UNSIGNED_ONLY + c.getInt(), true);
			settingsManager.mergeSettings(txn, update, NAMESPACE);
		});
	}

	private void forget(Transaction txn, ContactId c) throws DbException {
		Settings update = new Settings();
		update.put(UNSIGNED_ONLY + c.getInt(), "");
		settingsManager.mergeSettings(txn, update, NAMESPACE);
	}
}
