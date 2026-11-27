package com.professor.zerion.android.contact.add.remote;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.system.Clock;

import javax.inject.Inject;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.PER_ADD_BINDING_MS;

@NotNullByDefault
public class OwnLinkPrefsMigration implements OpenDatabaseHook {

	public static final class Registration {
	}

	static final String KEY_LINK = "own_link_last_shared";
	static final String KEY_AT = "own_link_last_shared_at";

	private final com.professor.zerion.android.profile.ProfileStorage storage;
	private final ContactManager contactManager;
	private final Clock clock;

	@Inject
	OwnLinkPrefsMigration(
			com.professor.zerion.android.profile.ProfileStorage storage,
			ContactManager contactManager, Clock clock) {
		this.storage = storage;
		this.contactManager = contactManager;
		this.clock = clock;
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		migrate(storage.preferences(), contactManager, txn,
				clock.currentTimeMillis());
	}

	static void migrate(SharedPreferences prefs, ContactManager contactManager,
			Transaction txn, long now) throws DbException {
		if (!prefs.contains(KEY_LINK) && !prefs.contains(KEY_AT)) return;
		String link = prefs.getString(KEY_LINK, null);
		long at = prefs.getLong(KEY_AT, 0);
		if (link != null && at > 0 && now - at <= PER_ADD_BINDING_MS) {
			try {
				contactManager.recordPerAddLinkShared(txn, link, at);
			} catch (FormatException e) {
			}
		}
		txn.attach(() -> prefs.edit().remove(KEY_LINK).remove(KEY_AT).commit());
	}
}
