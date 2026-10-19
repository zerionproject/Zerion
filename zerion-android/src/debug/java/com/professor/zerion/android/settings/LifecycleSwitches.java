package com.professor.zerion.android.settings;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.plugin.tor.auth.OnionAuthDebugHooks;

@NotNullByDefault
final class LifecycleSwitches implements OnionAuthDebugHooks {

	static final String NAMESPACE = "debug-lifecycle-tests";
	static final String SKIP_NEW_NEGOTIATIONS = "skipNewNegotiations";
	static final String HOLD_CONFIRMATIONS = "holdConfirmations";

	private final SettingsManager settingsManager;

	LifecycleSwitches(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	@Override
	public boolean skipsNewNegotiations(Transaction txn) throws DbException {
		return settingsManager.getSettings(txn, NAMESPACE)
				.getBoolean(SKIP_NEW_NEGOTIATIONS, false);
	}

	@Override
	public boolean holdsConfirmations(Transaction txn) throws DbException {
		return settingsManager.getSettings(txn, NAMESPACE)
				.getBoolean(HOLD_CONFIRMATIONS, false);
	}
}
