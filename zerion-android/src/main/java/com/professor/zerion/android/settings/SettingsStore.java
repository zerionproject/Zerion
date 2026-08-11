package com.professor.zerion.android.settings;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.professor.zerion.R;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceDataStore;

@NotNullByDefault
class SettingsStore extends PreferenceDataStore {

	private final Application app;
	private final SettingsManager settingsManager;
	private final Executor dbExecutor;
	private final String namespace;
	private final Handler main = new Handler(Looper.getMainLooper());

	SettingsStore(Application app,
			SettingsManager settingsManager,
			Executor dbExecutor,
			String namespace) {
		this.app = app;
		this.settingsManager = settingsManager;
		this.dbExecutor = dbExecutor;
		this.namespace = namespace;
	}

	@Override
	public void putBoolean(String key, boolean value) {
		Settings s = new Settings();
		s.putBoolean(key, value);
		storeSettings(s);
	}

	@Override
	public void putInt(String key, int value) {
		Settings s = new Settings();
		s.putInt(key, value);
		storeSettings(s);
	}

	@Override
	public void putString(String key, @Nullable String value) {
		Settings s = new Settings();
		s.put(key, value);
		storeSettings(s);
	}

	private void storeSettings(Settings s) {
		dbExecutor.execute(() -> {
			try {
				settingsManager.mergeSettings(s, namespace);
			} catch (DbException e) {
				main.post(() -> Toast.makeText(app,
						R.string.settings_save_failed,
						Toast.LENGTH_SHORT).show());
			}
		});
	}

}
