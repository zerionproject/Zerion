package org.briarproject.bramble.account;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.identity.IdentityManager;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;

import static java.util.Arrays.asList;
import static org.briarproject.bramble.util.IoUtils.deleteFileOrDir;
class AndroidAccountManager extends AccountManagerImpl
		implements AccountManager {
	
	private static final List<String> PROTECTED_DIR_NAMES =
			asList("cache", "code_cache", "lib", "shared_prefs");

	protected final Context appContext;
	private final SharedPreferences prefs;

	@Inject
	AndroidAccountManager(DatabaseConfig databaseConfig,
			CryptoComponent crypto, IdentityManager identityManager,
			SharedPreferences prefs, Application app) {
		super(databaseConfig, crypto, identityManager);
		this.prefs = prefs;
		appContext = app.getApplicationContext();
	}

	@Override
	public boolean accountExists() {
		boolean exists = super.accountExists();
		return exists;
	}

	@Override
	public void deleteAccount() {
		synchronized (stateChangeLock) {
			super.deleteAccount();
			SharedPreferences defaultPrefs = getDefaultSharedPreferences();
			deleteAppData(prefs, defaultPrefs);
		}
	}
	SharedPreferences getDefaultSharedPreferences() {
		return PreferenceManager.getDefaultSharedPreferences(appContext);
	}

	@GuardedBy("stateChangeLock")
	private void deleteAppData(SharedPreferences... clear) {
		for (SharedPreferences prefs : clear) {
			prefs.edit().clear().commit();
		}
		Set<File> files = new HashSet<>();
		File dataDir = getDataDir();
		@Nullable
		File[] fileArray = dataDir.listFiles();
		if (fileArray == null) {
		} else {
			for (File file : fileArray) {
				if (!PROTECTED_DIR_NAMES.contains(file.getName())) {
					files.add(file);
				}
			}
		}
		files.add(appContext.getFilesDir());
		addIfNotNull(files, appContext.getExternalCacheDir());
		for (File file : appContext.getExternalCacheDirs()) {
			addIfNotNull(files, file);
		}
		for (File file : appContext.getExternalMediaDirs()) {
			addIfNotNull(files, file);
		}
		File cacheDir = appContext.getCacheDir();
		File[] children = cacheDir.listFiles();
		if (children != null) files.addAll(asList(children));
		for (File file : files) {
			deleteFileOrDir(file);
		}
		// The sticker dir under getFilesDir()/stickers is already nuked
		// above; also drop the Keystore-resident AES alias so a re-add
		// of the same account doesn't carry forward an orphaned key.
		try {
			java.security.KeyStore ks =
					java.security.KeyStore.getInstance("AndroidKeyStore");
			ks.load(null);
			if (ks.containsAlias("zerion_sticker_aes_v1")) {
				ks.deleteEntry("zerion_sticker_aes_v1");
			}
		} catch (Exception ignored) {
		}
	}

	private File getDataDir() {
		return new File(appContext.getApplicationInfo().dataDir);
	}

	private void addIfNotNull(Set<File> files, @Nullable File file) {
		if (file != null) files.add(file);
	}
}
