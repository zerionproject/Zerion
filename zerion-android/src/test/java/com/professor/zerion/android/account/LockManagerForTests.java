package com.professor.zerion.android.account;

import android.app.Application;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.vault.VaultManager;

import org.zerionproject.core.api.settings.SettingsManager;

public final class LockManagerForTests {

	public LockManagerImpl create(Application app, SettingsManager settings,
			AndroidNotificationManager notifications, VaultManager vault) {
		return new LockManagerImpl(app, settings, notifications,
				Runnable::run, Runnable::run, () -> vault);
	}
}
