package com.professor.zerion.android.contact.add.remote;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public class OwnLinkMemory {

	public static final long SHARED_LINK_LIFETIME_MS = 2L * 24 * 60 * 60_000;

	static final String KEY_LINK = "own_link_last_shared";
	static final String KEY_AT = "own_link_last_shared_at";

	private final SharedPreferences prefs;

	public OwnLinkMemory(SharedPreferences prefs) {
		this.prefs = prefs;
	}

	public void shared(String link, long now) {
		prefs.edit().putString(KEY_LINK, link).putLong(KEY_AT, now).apply();
	}

	@Nullable
	public String lastShared(long now) {
		String link = prefs.getString(KEY_LINK, null);
		if (link == null) return null;
		long at = prefs.getLong(KEY_AT, 0);
		if (now - at > SHARED_LINK_LIFETIME_MS) return null;
		return link;
	}

	public String linkToBind(@Nullable String current, long now) {
		String shared = lastShared(now);
		if (shared != null) return shared;
		if (current == null) throw new IllegalStateException();
		return current;
	}

	public void forget() {
		prefs.edit().remove(KEY_LINK).remove(KEY_AT).apply();
	}
}
