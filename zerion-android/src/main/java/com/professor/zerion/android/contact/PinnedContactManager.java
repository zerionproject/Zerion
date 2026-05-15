package com.professor.zerion.android.contact;

import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class PinnedContactManager {

	private static final String PREF_PINNED_CONTACTS = "pinned_contact_ids";
	static final int MAX_PINNED = 5;

	private final SharedPreferences prefs;

	@Inject
	PinnedContactManager(@AppModule.UiPrefs SharedPreferences prefs) {
		this.prefs = prefs;
	}

	public boolean isPinned(ContactId contactId) {
		Set<String> pinned = prefs.getStringSet(PREF_PINNED_CONTACTS,
				new HashSet<>());
		return pinned != null &&
				pinned.contains(String.valueOf(contactId.getInt()));
	}

	public boolean togglePin(ContactId contactId) {
		Set<String> pinned = new HashSet<>(prefs.getStringSet(
				PREF_PINNED_CONTACTS, new HashSet<>()));
		String idStr = String.valueOf(contactId.getInt());
		boolean nowPinned;
		if (pinned.contains(idStr)) {
			pinned.remove(idStr);
			nowPinned = false;
		} else {
			if (pinned.size() >= MAX_PINNED) return false;
			pinned.add(idStr);
			nowPinned = true;
		}
		prefs.edit().putStringSet(PREF_PINNED_CONTACTS, pinned).apply();
		return nowPinned;
	}

	public int getPinnedCount() {
		Set<String> pinned = prefs.getStringSet(PREF_PINNED_CONTACTS,
				new HashSet<>());
		return pinned != null ? pinned.size() : 0;
	}

	public void unpin(ContactId contactId) {
		Set<String> pinned = new HashSet<>(prefs.getStringSet(
				PREF_PINNED_CONTACTS, new HashSet<>()));
		pinned.remove(String.valueOf(contactId.getInt()));
		prefs.edit().putStringSet(PREF_PINNED_CONTACTS, pinned).apply();
	}

	public void pruneStaleEntries(Set<ContactId> validContacts) {
		Set<String> pinned = new HashSet<>(prefs.getStringSet(
				PREF_PINNED_CONTACTS, new HashSet<>()));
		Set<String> validIds = new HashSet<>();
		for (ContactId c : validContacts) {
			validIds.add(String.valueOf(c.getInt()));
		}
		if (pinned.retainAll(validIds)) {
			prefs.edit().putStringSet(PREF_PINNED_CONTACTS, pinned)
					.apply();
		}
	}
}
