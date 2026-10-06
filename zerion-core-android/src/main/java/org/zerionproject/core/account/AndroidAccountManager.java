package org.zerionproject.core.account;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.account.ErasePolicy;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.IdentityManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.Arrays.asList;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_CIPHERTEXT;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_FILES_DAMAGED;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.zerionproject.core.util.IoUtils.deleteFileOrDir;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;
@Singleton
public class AndroidAccountManager extends AccountManagerImpl
		implements AccountManager {

	private static final String ERASE_MARKER_NAME = "erase.requested";

	private static final List<String> PROTECTED_DIR_NAMES =
			asList("cache", "code_cache", "lib", "shared_prefs",
					ERASE_MARKER_NAME);

	public enum ProfileCreationRefusal {
		PASSWORD_UNAVAILABLE,
		LOCKED_OUT,
		FAILED
	}

	protected final Context appContext;
	private final SharedPreferences prefs;
	private final ProfileManager profileManager;
	private final SecureRandom random = new SecureRandom();
	private final AtomicInteger derivations;

	@Nullable
	private volatile String lastProfileCreationError;

	@Nullable
	private volatile ProfileCreationRefusal lastProfileCreationRefusal;

	@GuardedBy("stateChangeLock")
	@Nullable
	private File keyDirOverride = null;

	@Inject
	AndroidAccountManager(DatabaseConfig databaseConfig,
			CryptoComponent crypto, IdentityManager identityManager,
			SharedPreferences prefs, Application app,
			ProfileManager profileManager) {
		this(databaseConfig, crypto, identityManager, prefs, app,
				profileManager, new AtomicInteger());
	}

	private AndroidAccountManager(DatabaseConfig databaseConfig,
			CryptoComponent crypto, IdentityManager identityManager,
			SharedPreferences prefs, Application app,
			ProfileManager profileManager, AtomicInteger derivations) {
		super(databaseConfig, countingDerivations(crypto, derivations),
				identityManager);
		this.derivations = derivations;
		this.prefs = prefs;
		this.profileManager = profileManager;
		appContext = app.getApplicationContext();
	}

	private static CryptoComponent countingDerivations(
			CryptoComponent delegate, AtomicInteger counter) {
		return (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					if (method.getName().equals("decryptWithPassword")) {
						counter.incrementAndGet();
					}
					try {
						return method.invoke(delegate, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	@Override
	protected File dbKeyFile() {
		File dir = keyDirOverride;
		return dir == null ? super.dbKeyFile()
				: new File(dir, super.dbKeyFile().getName());
	}

	@Override
	protected File dbKeyBackupFile() {
		File dir = keyDirOverride;
		return dir == null ? super.dbKeyBackupFile()
				: new File(dir, super.dbKeyBackupFile().getName());
	}

	@Override
	protected File dbKeyStateFile() {
		File dir = keyDirOverride;
		return dir == null ? super.dbKeyStateFile()
				: new File(dir, super.dbKeyStateFile().getName());
	}

	@Override
	protected File eraseMarkerFile() {
		File files = profileManager.getAppFilesRoot().getAbsoluteFile();
		File data = files.getParentFile();
		return new File(data == null ? files : data, ERASE_MARKER_NAME);
	}

	@Inject
	void injectErasePolicy(ErasePolicy policy) {
		setErasePolicy(policy);
	}

	@Override
	public boolean accountExists() {
		synchronized (stateChangeLock) {
			for (String id : profileManager.listProfileIds()) {
				if (profileManager.hasKeyFiles(id)) return true;
			}
			return false;
		}
	}

	@Override
	public boolean createAccount(String name, char[] password) {
		synchronized (stateChangeLock) {
			boolean created = super.createAccount(name, password);
			if (created) {
				profileManager.startSession(
						profileManager.getActiveProfileId());
			}
			return created;
		}
	}

	@Override
	public void signIn(char[] typed) throws DecryptionException {
		PasswordForms password = PasswordForms.of(typed);
		try {
			synchronized (stateChangeLock) {
				signInLocked(password);
			}
		} finally {
			password.clear();
		}
	}

	@GuardedBy("stateChangeLock")
	private void signInLocked(PasswordForms password)
			throws DecryptionException {
		{
			checkGlobalLockout();
			org.zerionproject.core.account.LoginThrottle.Attempt attempt =
					beginAttempt();
			List<String> profiles = profileManager.listProfileIds();
			if (profiles.isEmpty()) {
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
			SecretKey matchedKey = null;
			String matchedId = null;
			String matchedHex = null;
			boolean matchedNeedsUpgrade = false;
			int derivationsBefore = derivations.get();
			int tried = 0;
			List<String> damaged = new java.util.ArrayList<>();
			for (String id : order) {
				profileManager.setActiveProfileId(id);
				if (loadEncryptedDatabaseKey() == null) continue;
				tried++;
				try {
					StoredKey stored = openStoredKey(password);
					byte[] ciphertext = stored.ciphertext;
					byte[] plaintext = stored.plaintext;
					KeyStrengthener strengthener =
							databaseConfig.getKeyStrengthener();
					if (matchedKey != null) {
						java.util.Arrays.fill(plaintext, (byte) 0);
						continue;
					}
					matchedKey = new SecretKey(plaintext);
					matchedId = id;
					matchedHex = stored.hex;
					matchedNeedsUpgrade = needsReencryption(stored);
				} catch (DecryptionException e) {
					if (e.getDecryptionResult() == KEY_STRENGTHENER_ERROR) {
						if (matchedKey != null) matchedKey.clear();
						profileManager.setActiveProfileId(previousActive);
						cancelAttempt(attempt);
						throw e;
					}
					if (e.getDecryptionResult() == KEY_FILES_DAMAGED) {
						damaged.add(id);
					}
				}
			}
			padSignIn(derivations.get() - derivationsBefore, order, password);
			if (matchedKey != null) {
				profileManager.setActiveProfileId(matchedId);
				if (matchedNeedsUpgrade) {
					encryptAndReplaceDatabaseKey(matchedKey, password.normal);
				}
				alignKeyFilesWithPrimaryIfItHolds(matchedHex);
				materializePendingIdentityIfPresent(matchedId);
				setDatabaseKey(matchedKey);
				profileManager.writeLastActiveProfileId(matchedId);
				resetGlobalLockout();
				retireUnusedStrengthenerGenerations();
				profileManager.startSession(matchedId);
				return;
			}
			profileManager.setActiveProfileId(previousActive);
			boolean reportDamage = damaged.contains(order.get(0));
			if (reportDamage) {
				cancelAttempt(attempt);
			} else {
				applyErasePolicy(tried > 0 && damaged.isEmpty());
			}
			throw new DecryptionException(
					reportDamage ? KEY_FILES_DAMAGED : INVALID_CIPHERTEXT);
		}
	}

	static final int MIN_TIMED_ATTEMPTS = 2;

	@GuardedBy("stateChangeLock")
	private void padSignIn(int performed, List<String> order,
			PasswordForms password) {
		if (order.isEmpty()) return;
		int target = MIN_TIMED_ATTEMPTS * password.count();
		if (performed >= target) return;
		profileManager.setActiveProfileId(order.get(0));
		String hex = loadEncryptedDatabaseKey();
		if (hex == null) return;
		for (int i = performed; i < target; i++) {
			padOnce(hex, password.legacy == null || i % 2 == 0
					? password.normal : password.legacy);
		}
	}

	private void padOnce(String hex, char[] password) {
		try {
			byte[] plaintext = crypto.decryptWithPassword(
					fromHexString(hex), password,
					databaseConfig.getKeyStrengthener());
			java.util.Arrays.fill(plaintext, (byte) 0);
		} catch (DecryptionException
				| org.zerionproject.core.api.FormatException ignored) {
		}
	}

	@GuardedBy("stateChangeLock")
	private boolean passwordOpensAnyProfile(PasswordForms password,
			@Nullable String skip) throws DecryptionException {
		int derivationsBefore = derivations.get();
		boolean opens = false;
		String first = null;
		for (String id : profileManager.listProfileIds()) {
			if (!profileManager.hasKeyFiles(id)) continue;
			if (first == null) first = id;
			if (id.equals(skip)) continue;
			keyDirOverride = profileManager.getKeyDirWithoutCreating(id);
			try {
				byte[] plaintext = openStoredKey(password, false).plaintext;
				java.util.Arrays.fill(plaintext, (byte) 0);
				opens = true;
			} catch (DecryptionException e) {
				if (e.getDecryptionResult() == KEY_STRENGTHENER_ERROR) {
					throw e;
				}
			} finally {
				keyDirOverride = null;
			}
		}
		if (first != null) {
			int target = MIN_TIMED_ATTEMPTS * password.count();
			while (derivations.get() - derivationsBefore < target) {
				int before = derivations.get();
				keyDirOverride = profileManager.getKeyDirWithoutCreating(first);
				try {
					byte[] plaintext = openStoredKey(password, false).plaintext;
					java.util.Arrays.fill(plaintext, (byte) 0);
				} catch (DecryptionException e) {
					if (e.getDecryptionResult() == KEY_STRENGTHENER_ERROR) {
						throw e;
					}
				} finally {
					keyDirOverride = null;
				}
				if (derivations.get() == before) break;
			}
		}
		return opens;
	}

	@GuardedBy("stateChangeLock")
	private boolean storeKeyFor(String profileId, String hex) {
		keyDirOverride = profileManager.getKeyDir(profileId);
		try {
			return storeEncryptedDatabaseKey(hex);
		} finally {
			keyDirOverride = null;
		}
	}

	@GuardedBy("stateChangeLock")
	private void materializePendingIdentityIfPresent(String profileId) {
		String name = readPendingIdentityName(profileId);
		if (name == null) return;
		org.zerionproject.core.api.identity.Identity identity =
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
		byte[] ciphertext;
		try {
			startStrengthenerGeneration();
			ciphertext = crypto.encryptWithPassword(plaintext, password,
					databaseConfig.getKeyStrengthener());
		} catch (org.zerionproject.core.api.crypto
				.KeyStrengthenerException keepExisting) {
			return;
		}
		storeEncryptedDatabaseKey(toHexString(ciphertext));
	}

	@Override
	protected LoginThrottle createLoginThrottle(File ignored) {
		return new LoginThrottle(
				LoginThrottle.fileStore(profileManager.getLockoutFile()),
				android.os.SystemClock::elapsedRealtime,
				LoginThrottle.linuxBootId(), LoginThrottle.SIGN_IN);
	}

	static final LoginThrottle.Policy PASSWORD_CHECK = new LoginThrottle.Policy() {
		@Override
		public int freeFailures() {
			return 3;
		}

		@Override
		public long lockoutMs(int failures) {
			int doublings = Math.max(0, failures - 4);
			long duration = 300_000L << Math.min(doublings, 20);
			return Math.min(duration, 86_400_000L);
		}

		@Override
		public long decayMs() {
			return 86_400_000L;
		}
	};

	protected LoginThrottle createPasswordCheckThrottle() {
		return new LoginThrottle(LoginThrottle.fileStore(
				profileManager.getPasswordCheckLockoutFile()),
				android.os.SystemClock::elapsedRealtime,
				LoginThrottle.linuxBootId(), PASSWORD_CHECK);
	}

	@Nullable
	private LoginThrottle passwordCheckThrottle;

	@GuardedBy("stateChangeLock")
	private LoginThrottle passwordCheckThrottle() {
		LoginThrottle t = passwordCheckThrottle;
		if (t == null) {
			t = createPasswordCheckThrottle();
			passwordCheckThrottle = t;
		}
		return t;
	}

	@GuardedBy("stateChangeLock")
	private boolean admitPasswordCheck() {
		LoginThrottle t = passwordCheckThrottle();
		if (t.remainingLockoutMs() > 0) return false;
		t.recordFailure();
		return t.remainingLockoutMs() == 0;
	}

	@GuardedBy("stateChangeLock")
	private void checkGlobalLockout() throws DecryptionException {
		checkLockout();
	}

	@GuardedBy("stateChangeLock")
	private void resetGlobalLockout() {
		resetLockout();
	}

	public String getActiveProfileId() {
		return profileManager.getActiveProfileId();
	}

	@Nullable
	public String readActiveDisplayName() {
		return profileManager.readDisplayName(
				profileManager.getActiveProfileId());
	}

	public void ensureActiveDisplayName(String fallbackName) {
		String id = profileManager.getActiveProfileId();
		if (profileManager.readDisplayName(id) != null) return;
		if (fallbackName == null || fallbackName.isEmpty()) return;
		profileManager.writeDisplayName(id, fallbackName);
	}

	@Nullable
	public String scheduleProfileCreation(String displayName, char[] typed) {
		PasswordForms password = PasswordForms.of(typed);
		try {
			synchronized (stateChangeLock) {
				return scheduleProfileCreationLocked(displayName, password);
			}
		} finally {
			password.clear();
		}
	}

	@GuardedBy("stateChangeLock")
	@Nullable
	private String scheduleProfileCreationLocked(String displayName,
			PasswordForms password) {
		{
			lastProfileCreationError = null;
			lastProfileCreationRefusal = null;
			if (isEmptyPassword(password.normal)) {
				refuse(ProfileCreationRefusal.FAILED,
						"password must not be empty");
				return null;
			}
			if (!passwordCanProtectNewProfile(password)) return null;
			String newId = profileManager.generateProfileId();
			if (!profileManager.createProfileDir(newId)) {
				refuse(ProfileCreationRefusal.FAILED,
						"createProfileDir failed");
				return null;
			}
			try {
				SecretKey freshKey = crypto.generateSecretKey();
				byte[] plaintext = freshKey.getBytes();
				startStrengthenerGeneration();
				byte[] ciphertext = crypto.encryptWithPassword(plaintext,
						password.normal, databaseConfig.getKeyStrengthener());
				boolean ok = storeKeyFor(newId, toHexString(ciphertext));
				if (!ok) {
					refuse(ProfileCreationRefusal.FAILED,
							"storeEncryptedDatabaseKey failed");
					profileManager.secureWipeProfile(newId);
					return null;
				}
				if (!writePendingIdentityName(newId, displayName)
						|| !profileManager.writeDisplayName(newId,
								displayName)) {
					refuse(ProfileCreationRefusal.FAILED,
							"profile metadata write failed");
					profileManager.secureWipeProfile(newId);
					return null;
				}
				freshKey.clear();
				return newId;
			} catch (Exception e) {
				refuse(ProfileCreationRefusal.FAILED, describe(e));
				profileManager.secureWipeProfile(newId);
				return null;
			}
		}
	}

	@Nullable
	public String importProfile(String displayName, char[] typed,
			byte[] dbBytes, byte[] dbKey) {
		PasswordForms password = PasswordForms.of(typed);
		try {
			return importProfile(displayName, password, dbBytes, dbKey);
		} finally {
			password.clear();
		}
	}

	@Nullable
	private String importProfile(String displayName, PasswordForms password,
			byte[] dbBytes, byte[] dbKey) {
		lastProfileCreationRefusal = null;
		if (isEmptyPassword(password.normal)) {
			refuse(ProfileCreationRefusal.FAILED,
					"password must not be empty");
			return null;
		}
		synchronized (stateChangeLock) {
			lastProfileCreationError = null;
			if (!passwordCanProtectNewProfile(password)) return null;
			String newId = profileManager.generateProfileId();
			if (!profileManager.createProfileDir(newId)) {
				refuse(ProfileCreationRefusal.FAILED,
						"createProfileDir failed");
				return null;
			}
			try {
				File dbFile = new File(profileManager.getDbDir(newId),
						"db.sqlite");
				try (java.io.FileOutputStream out =
						new java.io.FileOutputStream(dbFile)) {
					out.write(dbBytes);
					out.getFD().sync();
				}
				startStrengthenerGeneration();
				byte[] ciphertext = crypto.encryptWithPassword(dbKey,
						password.normal, databaseConfig.getKeyStrengthener());
				boolean ok = storeKeyFor(newId, toHexString(ciphertext));
				if (!ok) {
					refuse(ProfileCreationRefusal.FAILED,
							"storeEncryptedDatabaseKey failed");
					profileManager.secureWipeProfile(newId);
					return null;
				}
				if (!profileManager.writeDisplayName(newId, displayName)) {
					refuse(ProfileCreationRefusal.FAILED,
							"writeDisplayName failed");
					profileManager.secureWipeProfile(newId);
					return null;
				}
				return newId;
			} catch (Exception e) {
				refuse(ProfileCreationRefusal.FAILED, describe(e));
				profileManager.secureWipeProfile(newId);
				return null;
			}
		}
	}

	@GuardedBy("stateChangeLock")
	private boolean passwordCanProtectNewProfile(PasswordForms password) {
		try {
			checkGlobalLockout();
		} catch (DecryptionException e) {
			refuse(ProfileCreationRefusal.LOCKED_OUT, "locked out");
			return false;
		}
		if (!admitPasswordCheck()) {
			refuse(ProfileCreationRefusal.LOCKED_OUT,
					"password checks locked out");
			return false;
		}
		try {
			if (passwordOpensAnyProfile(password, null)) {
				refuse(ProfileCreationRefusal.PASSWORD_UNAVAILABLE, null);
				return false;
			}
		} catch (DecryptionException e) {
			refuse(ProfileCreationRefusal.FAILED,
					"key strengthener unavailable");
			return false;
		}
		return true;
	}

	private void refuse(ProfileCreationRefusal refusal,
			@Nullable String detail) {
		lastProfileCreationRefusal = refusal;
		lastProfileCreationError = detail;
	}

	private static String describe(Exception e) {
		return e.getClass().getSimpleName()
				+ (e.getMessage() != null ? ": " + e.getMessage() : "");
	}

	@Nullable
	public String getLastProfileCreationError() {
		return lastProfileCreationError;
	}

	@Nullable
	public ProfileCreationRefusal getLastProfileCreationRefusal() {
		return lastProfileCreationRefusal;
	}

	@Override
	public void changePassword(char[] oldPassword, char[] newPassword)
			throws DecryptionException {
		PasswordForms newForms = PasswordForms.of(newPassword);
		try {
			synchronized (stateChangeLock) {
				if (hasDatabaseKey() && !isEmptyPassword(newForms.normal)) {
					verifyPassword(oldPassword);
					if (!admitPasswordCheck()) {
						throw new DecryptionException(INVALID_CIPHERTEXT);
					}
					if (passwordOpensAnyProfile(newForms,
							profileManager.getActiveProfileId())) {
						throw new PasswordUnavailableException();
					}
				}
				super.changePassword(oldPassword, newPassword);
			}
		} finally {
			newForms.clear();
		}
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
	private String readPendingIdentityName(String profileId) {
		return profileManager.readEncryptedMetaFile(profileId,
				"pending_identity_name");
	}

	public boolean deleteActiveProfile(String expectedProfileId) {
		synchronized (stateChangeLock) {
			String id = profileManager.getActiveProfileId();
			if (id == null || !id.equals(expectedProfileId)) return false;
			if (anotherProfileHoldsAKey(id)) encryptForNothing();
			else createPlaceholderProfile();
			profileManager.shredProfileKeys(id);
			profileManager.forgetLastActiveProfileId(id);
			return true;
		}
	}

	@GuardedBy("stateChangeLock")
	private void encryptForNothing() {
		byte[] secret = new byte[32];
		byte[] seed = new byte[24];
		random.nextBytes(secret);
		random.nextBytes(seed);
		char[] password = toHexString(seed).toCharArray();
		try {
			byte[] ciphertext = crypto.encryptWithPassword(secret, password,
					databaseConfig.getKeyStrengthener());
			java.util.Arrays.fill(ciphertext, (byte) 0);
		} catch (RuntimeException ignored) {
		} finally {
			java.util.Arrays.fill(secret, (byte) 0);
			java.util.Arrays.fill(seed, (byte) 0);
			java.util.Arrays.fill(password, '\0');
		}
	}

	@GuardedBy("stateChangeLock")
	private boolean anotherProfileHoldsAKey(String id) {
		for (String other : profileManager.listProfileIds()) {
			if (!other.equals(id) && profileManager.hasKeyFiles(other)) {
				return true;
			}
		}
		return false;
	}

	@GuardedBy("stateChangeLock")
	private void createPlaceholderProfile() {
		String placeholder = profileManager.generateProfileId();
		if (!profileManager.createProfileDir(placeholder)) return;
		byte[] secret = new byte[32];
		byte[] seed = new byte[24];
		random.nextBytes(secret);
		random.nextBytes(seed);
		char[] password = toHexString(seed).toCharArray();
		boolean stored = false;
		try {
			startStrengthenerGeneration();
			byte[] ciphertext = crypto.encryptWithPassword(secret, password,
					databaseConfig.getKeyStrengthener());
			stored = storeKeyFor(placeholder, toHexString(ciphertext));
		} catch (RuntimeException ignored) {
		} finally {
			java.util.Arrays.fill(secret, (byte) 0);
			java.util.Arrays.fill(seed, (byte) 0);
			java.util.Arrays.fill(password, '\0');
		}
		if (!stored) profileManager.secureWipeProfile(placeholder);
	}

	@Override
	protected void shredKeyFiles() {
		for (String id : profileManager.listProfileIds()) {
			deleteFileOrDir(profileManager.getKeyDirWithoutCreating(id));
		}
	}

	@Nullable
	@Override
	protected Set<Integer> strengtheningGenerationsInUse() {
		List<String> ids = profileManager.listProfileIdsOrNull();
		if (ids == null) return null;
		Set<Integer> inUse = new HashSet<>();
		for (String id : ids) {
			if (!addGenerationOf(profileManager.getDbKeyFile(id), inUse)
					|| !addGenerationOf(profileManager.getDbKeyBackupFile(id),
					inUse)) {
				return null;
			}
		}
		return inUse;
	}

	@Override
	public void deleteAccount() {
		super.deleteAccount();
	}

	@Override
	protected void deleteAccountData() {
		SharedPreferences defaultPrefs = getDefaultSharedPreferences();
		deleteAppData(prefs, defaultPrefs);
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
		KeyStrengthener strengthener = databaseConfig.getKeyStrengthener();
		if (strengthener != null) {
			try {
				strengthener.discardKeyBeforeFirstAccount();
			} catch (RuntimeException ignored) {
			}
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
