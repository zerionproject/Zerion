package com.professor.zerion.android.login;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.account.ErasePolicy;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class SignInErasePolicy implements ErasePolicy {

	static final String KEY_WIPE_ON_FAILURES = "bf_wipe";
	static final int ATTEMPTS_BEFORE_WIPE = 6;

	private final SharedPreferences prefs;

	public SignInErasePolicy(SharedPreferences prefs) {
		this.prefs = prefs;
	}

	boolean isEnabled() {
		return prefs.getBoolean(KEY_WIPE_ON_FAILURES, false);
	}

	void setEnabled(boolean enabled) {
		prefs.edit().putBoolean(KEY_WIPE_ON_FAILURES, enabled).commit();
	}

	@Override
	public boolean eraseDue(int consecutiveFailures) {
		return consecutiveFailures >= ATTEMPTS_BEFORE_WIPE && isEnabled();
	}
}
