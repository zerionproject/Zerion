package org.briarproject.bramble.account;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.DecryptionException;
import org.briarproject.bramble.api.crypto.KeyStrengthener;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.identity.IdentityManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.Arrays.asList;
import static org.briarproject.bramble.api.crypto.DecryptionResult.INVALID_CIPHERTEXT;
import static org.briarproject.bramble.util.IoUtils.deleteFileOrDir;
import static org.briarproject.bramble.util.StringUtils.UTF_8;
import static org.briarproject.bramble.util.StringUtils.fromHexString;
@Singleton
public class AndroidAccountManager extends AccountManagerImpl
		implements AccountManager {

	private static final List<String> PROTECTED_DIR_NAMES =
			asList("cache", "code_cache", "lib", "shared_prefs");

	protected final Context appContext;
	private final SharedPreferences prefs;
	private final ProfileManager profileManager;

	@Nullable
	private volatile String lastProfileCreationError;

	@Inject
	AndroidAccountManager(DatabaseConfig databaseConfig,
			CryptoComponent crypto, IdentityManager identityManager,
			SharedPreferences prefs, Application app,
			ProfileManager profileManager) {
		super(databaseConfig, crypto, identityManager);
		this.prefs = prefs;
		this.profileManager = profileManager;
		appContext = app.getApplicationContext();
	}

	@Override
	public boolean accountExists() {
		synchronized (stateChangeLock) {
			for (String id : profileManager.listProfileIds()) {
				if (profileManager.getDbKeyFile(id).exists()
						|| profileManager.getDbKeyBackupFile(id).exists()) {
					return true;
				}
			}
			return false;
		}
	}

	@Override
	public void signIn(char[] password) throws DecryptionException {
		synchronized (stateChangeLock) {
			checkGlobalLockout();
			List<String> profiles = profileManager.listProfileIds();
			if (profiles.isEmpty()) {
				recordGlobalFailedAttempt();
				throw new DecryptionException(INVALID_CIPHERTEXT);
			}
			String previousActive = profileManager.getActiveProfileId();
			String hint = profileManager.readLastActiveProfileId();
			List<String> order = new java.util.ArrayList<>(profiles.size());
			if (hint != null && profiles.contains(hint)) {
				order.add(hint);
				for (String id : profiles) {
					if (!id.equals(hint)) order.add(id);
				}
			} else {
				order.addAll(profiles);
			}
			int decryptCount = 0;
			String decryptedId = null;
			for (String id : order) {
				profileManager.setActiveProfileId(id);
				String hex = loadEncryptedDatabaseKey();
				if (hex == null) continue;
				try {
					byte[] ciphertext = fromHexString(hex);
					KeyStrengthener strengthener =
							databaseConfig.getKeyStrengthener();
					byte[] plaintext = crypto.decryptWithPassword(ciphertext,
							password, strengthener);
					SecretKey key = new SecretKey(plaintext);
					if (decryptCount > 0) {
						key.clear();
						profileManager.setActiveProfileId(previousActive);
						recordGlobalFailedAttempt();
						throw new DecryptionException(INVALID_CIPHERTEXT);
					}
					boolean needsStrengthenerUpgrade = strengthener != null
							&& !crypto.isEncryptedWithStrengthenedKey(
									ciphertext);
					boolean needsKdfUpgrade =
							crypto.isEncryptedWithLegacyKdf(ciphertext);
					if (needsStrengthenerUpgrade || needsKdfUpgrade) {
						encryptAndReplaceDatabaseKey(key, password);
					}
					materializePendingIdentityIfPresent(id);
					setDatabaseKey(key);
					decryptedId = id;
					decryptCount++;
				} catch (DecryptionException ignored) {
				} catch (org.briarproject.bramble.api.FormatException
						ignored) {
				}
			}
			if (decryptCount == 1 && decryptedId != null) {
				profileManager.setActiveProfileId(decryptedId);
				profileManager.writeLastActiveProfileId(decryptedId);
				resetGlobalLockout();
				return;
			}
			profileManager.setActiveProfileId(previousActive);
			recordGlobalFailedAttempt();
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
	}

	@GuardedBy("stateChangeLock")
	private void materializePendingIdentityIfPresent(String profileId) {
		String name = readPendingIdentityName(profileId);
		if (name == null) return;
		org.briarproject.bramble.api.identity.Identity identity =
				identityManager.createIdentity(name);
		identityManager.registerIdentity(identity);
	}

	public void confirmAccountStarted() {
		synchronized (stateChangeLock) {
			String id = profileManager.getActiveProfileId();
			profileManager.deleteMetaFile(id, "pending_identity_name");
		}
	}

	@GuardedBy("stateChangeLock")
	private void encryptAndReplaceDatabaseKey(SecretKey key, char[] password) {
		byte[] plaintext = key.getBytes();
		byte[] ciphertext = crypto.encryptWithPassword(plaintext, password,
				databaseConfig.getKeyStrengthener());
		storeEncryptedDatabaseKey(
				org.briarproject.bramble.util.StringUtils.toHexString(
						ciphertext));
	}

	@GuardedBy("stateChangeLock")
	private void checkGlobalLockout() throws DecryptionException {
		File lockoutFile = profileManager.getLockoutFile();
		if (!lockoutFile.exists()) return;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(lockoutFile), UTF_8))) {
			String line = reader.readLine();
			if (line == null) return;
			String[] parts = line.split(",");
			if (parts.length != 2) return;
			int attempts = Integer.parseInt(parts[0]);
			long lastFailTime = Long.parseLong(parts[1]);
			if (attempts >= 10) {
				long elapsed = System.currentTimeMillis() - lastFailTime;
				if (elapsed < 5L * 60 * 1000) {
					throw new DecryptionException(INVALID_CIPHERTEXT);
				}
				resetGlobalLockout();
			}
		} catch (IOException | NumberFormatException e) {

			lockoutFile.delete();
		}
	}

	@GuardedBy("stateChangeLock")
	private void recordGlobalFailedAttempt() {
		File lockoutFile = profileManager.getLockoutFile();
		int attempts = 0;
		if (lockoutFile.exists()) {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(new FileInputStream(lockoutFile),
							UTF_8))) {
				String line = reader.readLine();
				if (line != null) {
					String[] parts = line.split(",");
					if (parts.length == 2) {
						attempts = Integer.parseInt(parts[0]);
					}
				}
			} catch (IOException | NumberFormatException ignored) {
			}
		}
		attempts++;
		try (java.io.FileOutputStream out =
				new java.io.FileOutputStream(lockoutFile)) {
			String data = attempts + "," + System.currentTimeMillis();
			out.write(data.getBytes(UTF_8));
			out.flush();
		} catch (IOException ignored) {
		}
	}

	@GuardedBy("stateChangeLock")
	private void resetGlobalLockout() {
		File lockoutFile = profileManager.getLockoutFile();
		if (lockoutFile.exists()) {

			lockoutFile.delete();
		}
	}

	public String getActiveProfileId() {
		return profileManager.getActiveProfileId();
	}

	public int profileCount() {
		return profileManager.listProfileIds().size();
	}

	public java.util.List<String> listProfileIds() {
		return profileManager.listProfileIds();
	}

	@Nullable
	public String readDisplayName(String profileId) {
		return profileManager.readDisplayName(profileId);
	}

	public void ensureActiveDisplayName(String fallbackName) {
		String id = profileManager.getActiveProfileId();
		if (profileManager.readDisplayName(id) != null) return;
		if (fallbackName == null || fallbackName.isEmpty()) return;
		profileManager.writeDisplayName(id, fallbackName);
	}

	@Nullable
	public String scheduleProfileCreation(String displayName, char[] password) {
		synchronized (stateChangeLock) {
			lastProfileCreationError = null;
			String newId = profileManager.generateProfileId();
			if (!profileManager.createProfileDir(newId)) {
				lastProfileCreationError = "createProfileDir failed";
				return null;
			}
			String previousActive = profileManager.getActiveProfileId();
			try {
				profileManager.setActiveProfileId(newId);
				SecretKey freshKey = crypto.generateSecretKey();
				byte[] plaintext = freshKey.getBytes();
				byte[] ciphertext = crypto.encryptWithPassword(plaintext,
						password, databaseConfig.getKeyStrengthener());
				boolean ok = storeEncryptedDatabaseKey(
						org.briarproject.bramble.util.StringUtils.toHexString(
								ciphertext));
				if (!ok) {
					lastProfileCreationError = "storeEncryptedDatabaseKey failed";
					profileManager.secureWipeProfile(newId);
					return null;
				}
				if (!writePendingIdentityName(newId, displayName)
						|| !profileManager.writeDisplayName(newId,
								displayName)) {
					lastProfileCreationError = "profile metadata write failed";
					profileManager.secureWipeProfile(newId);
					return null;
				}
				freshKey.clear();
				return newId;
			} catch (Exception e) {
				lastProfileCreationError = e.getClass().getSimpleName()
						+ (e.getMessage() != null ? ": " + e.getMessage() : "");
				profileManager.secureWipeProfile(newId);
				return null;
			} finally {
				profileManager.setActiveProfileId(previousActive);
			}
		}
	}

	@Nullable
	public String importProfile(String displayName, char[] password,
			byte[] dbBytes, byte[] dbKey) {
		synchronized (stateChangeLock) {
			String newId = profileManager.generateProfileId();
			if (!profileManager.createProfileDir(newId)) {
				lastProfileCreationError = "createProfileDir failed";
				return null;
			}
			String previousActive = profileManager.getActiveProfileId();
			try {
				profileManager.setActiveProfileId(newId);
				File dbFile = new File(profileManager.getDbDir(newId),
						"db.sqlite");
				try (java.io.FileOutputStream out =
						new java.io.FileOutputStream(dbFile)) {
					out.write(dbBytes);
					out.getFD().sync();
				}
				byte[] ciphertext = crypto.encryptWithPassword(dbKey, password,
						databaseConfig.getKeyStrengthener());
				boolean ok = storeEncryptedDatabaseKey(
						org.briarproject.bramble.util.StringUtils.toHexString(
								ciphertext));
				if (!ok) {
					lastProfileCreationError = "storeEncryptedDatabaseKey failed";
					profileManager.secureWipeProfile(newId);
					return null;
				}
				if (!profileManager.writeDisplayName(newId, displayName)) {
					lastProfileCreationError = "writeDisplayName failed";
					profileManager.secureWipeProfile(newId);
					return null;
				}
				return newId;
			} catch (Exception e) {
				lastProfileCreationError = e.getClass().getSimpleName()
						+ (e.getMessage() != null ? ": " + e.getMessage() : "");
				profileManager.secureWipeProfile(newId);
				return null;
			} finally {
				profileManager.setActiveProfileId(previousActive);
			}
		}
	}

	@Nullable
	public String getLastProfileCreationError() {
		return lastProfileCreationError;
	}

	private boolean writePendingIdentityName(String profileId, String name) {
		try {
			profileManager.writeEncryptedMetaFile(profileId,
					"pending_identity_name", name);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	@Nullable
	public String readPendingIdentityName(String profileId) {
		return profileManager.readEncryptedMetaFile(profileId,
				"pending_identity_name");
	}

	public void clearPendingIdentityName(String profileId) {
		profileManager.deleteMetaFile(profileId, "pending_identity_name");
	}

	public void deleteActiveProfile() {
		synchronized (stateChangeLock) {
			String id = profileManager.getActiveProfileId();
			profileManager.secureWipeProfile(id);
		}
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
		try {
			java.security.KeyStore ks =
					java.security.KeyStore.getInstance("AndroidKeyStore");
			ks.load(null);
			if (ks.containsAlias("zerion_sticker_aes_v1")) {
				ks.deleteEntry("zerion_sticker_aes_v1");
			}
			if (ks.containsAlias("db")) {
				ks.deleteEntry("db");
			}
			if (ks.containsAlias("_androidx_security_master_key_")) {
				ks.deleteEntry("_androidx_security_master_key_");
			}
		} catch (Exception ignored) {
		}
		profileManager.deleteProfileMetadataKey();
	}

	private File getDataDir() {
		return new File(appContext.getApplicationInfo().dataDir);
	}

	private void addIfNotNull(Set<File> files, @Nullable File file) {
		if (file != null) files.add(file);
	}
}
