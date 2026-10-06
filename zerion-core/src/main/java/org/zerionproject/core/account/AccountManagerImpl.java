package org.zerionproject.core.account;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.account.ErasePolicy;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.KeyStrengthenerException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.lifecycle.ServiceException;
import org.zerionproject.core.util.IoUtils;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_CIPHERTEXT;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_PASSWORD;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_FILES_DAMAGED;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_REPLACEMENT_FAILED;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_REPLACEMENT_UNCERTAIN;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.zerionproject.core.api.crypto.DecryptionResult.SUCCESS;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class AccountManagerImpl implements AccountManager, Service {
	private static final String DB_KEY_FILENAME = "db.key";
	private static final String DB_KEY_BACKUP_FILENAME = "db.key.bak";
	private static final String DB_KEY_STATE_FILENAME = "db.key.state";
	private static final String LOCKOUT_FILENAME = "login.lockout";
	private static final String ERASE_MARKER_FILENAME = "erase.requested";

	protected final DatabaseConfig databaseConfig;
	protected final CryptoComponent crypto;
	protected final IdentityManager identityManager;

	final Object stateChangeLock = new Object();

	@Nullable
	private volatile SecretKey databaseKey = null;
	@Nullable
	private volatile String lastCreateAccountError = null;
	@Nullable
	private volatile ErasePolicy erasePolicy = null;

	@Inject
	AccountManagerImpl(DatabaseConfig databaseConfig, CryptoComponent crypto,
			IdentityManager identityManager) {
		this.databaseConfig = databaseConfig;
		this.crypto = crypto;
		this.identityManager = identityManager;
	}

	protected File dbKeyFile() {
		return new File(databaseConfig.getDatabaseKeyDirectory(),
				DB_KEY_FILENAME);
	}

	protected File dbKeyBackupFile() {
		return new File(databaseConfig.getDatabaseKeyDirectory(),
				DB_KEY_BACKUP_FILENAME);
	}

	protected File dbKeyStateFile() {
		return new File(databaseConfig.getDatabaseKeyDirectory(),
				DB_KEY_STATE_FILENAME);
	}

	protected File lockoutFile() {
		return new File(databaseConfig.getDatabaseKeyDirectory(),
				LOCKOUT_FILENAME);
	}

	protected File eraseMarkerFile() {
		File keyDir =
				databaseConfig.getDatabaseKeyDirectory().getAbsoluteFile();
		File parent = keyDir.getParentFile();
		return new File(parent == null ? keyDir : parent,
				ERASE_MARKER_FILENAME);
	}

	public void setErasePolicy(@Nullable ErasePolicy policy) {
		erasePolicy = policy;
	}

	@Override
	public void startService() throws ServiceException {
	}

	@Override
	public void stopService() throws ServiceException {
		synchronized (stateChangeLock) {
			if (databaseKey != null) {
				databaseKey.clear();
				databaseKey = null;
			}
		}
	}

	@Override
	public boolean hasDatabaseKey() {
		return databaseKey != null;
	}

	@Override
	@Nullable
	public SecretKey getDatabaseKey() {
		return databaseKey;
	}

	@GuardedBy("stateChangeLock")
	@Nullable
	String loadEncryptedDatabaseKey() {
		String key = readDbKeyFromFile(dbKeyFile());
		if (key == null) {
			key = readDbKeyFromFile(dbKeyBackupFile());
		}
		return key;
	}

	@GuardedBy("stateChangeLock")
	@Nullable
	private String readDbKeyFromFile(File f) {
		if (!f.exists()) {
			return null;
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(f), UTF_8))) {
			return reader.readLine();
		} catch (IOException e) {
			return null;
		}
	}

	@GuardedBy("stateChangeLock")
	boolean storeEncryptedDatabaseKey(String hex) {
		File primary = dbKeyFile();
		File keyDir = primary.getAbsoluteFile().getParentFile();
		if (keyDir != null) keyDir.mkdirs();
		File backup = dbKeyBackupFile();
		File state = dbKeyStateFile();
		boolean primaryExisted = primary.exists();
		byte[] bytes = hex.getBytes(UTF_8);
		String previousBackup = readDbKeyFromFile(backup);
		String previousState = readDbKeyFromFile(state);
		try {
			writeKeyFile(backup, bytes);
		} catch (IOException | RuntimeException e) {
			return false;
		}
		try {
			writeKeyFile(state, keyState(hex).getBytes(UTF_8));
		} catch (IOException | RuntimeException e) {
			if (previousBackup != null) {
				writeQuietly(backup, previousBackup);
			} else if (!primaryExisted) {
				backup.delete();
			}
			return false;
		}
		try {
			writeKeyFile(primary, bytes);
			return true;
		} catch (IOException | RuntimeException e) {
			if (!primaryExisted && !primary.exists()) {
				putBackOrDelete(state, previousState);
				putBackOrDelete(backup, previousBackup);
			}
			return false;
		}
	}

	@GuardedBy("stateChangeLock")
	private void putBackOrDelete(File file, @Nullable String previous) {
		if (previous != null) {
			writeQuietly(file, previous);
		} else {
			file.delete();
		}
	}

	static String keyState(String hex) {
		try {
			return toHexString(java.security.MessageDigest.getInstance("SHA-256")
					.digest(hex.getBytes(UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	@GuardedBy("stateChangeLock")
	private void writeQuietly(File f, String content) {
		try {
			writeKeyFile(f, content.getBytes(UTF_8));
		} catch (IOException | RuntimeException ignored) {
		}
	}

	protected void writeKeyFile(File f, byte[] bytes) throws IOException {
		LoginThrottle.writeDurably(f, bytes);
	}

	@Override
	public boolean accountExists() {
		synchronized (stateChangeLock) {
			return loadEncryptedDatabaseKey() != null;
		}
	}

	@Override
	public boolean createAccount(String name, @Nullable char[] typed) {
		char[] password = typed == null ? new char[0]
				: PasswordNormalizer.normalize(typed);
		try {
			return createAccountLocked(name, password);
		} finally {
			java.util.Arrays.fill(password, '\0');
		}
	}

	private boolean createAccountLocked(String name, char[] password) {
		synchronized (stateChangeLock) {
			if (hasDatabaseKey())
				throw new AssertionError("Already have a database key");
			if (isEmptyPassword(password)) {
				lastCreateAccountError = "empty password";
				return false;
			}
			KeyStrengthener strengthener = databaseConfig.getKeyStrengthener();
			if (strengthener != null && !accountExists()) {
				strengthener.discardKeyBeforeFirstAccount();
			}
			Identity identity = identityManager.createIdentity(name);
			identityManager.registerIdentity(identity);
			SecretKey key = crypto.generateSecretKey();
			boolean stored;
			try {
				stored = encryptAndStoreDatabaseKey(key, password);
				if (!stored) lastCreateAccountError = "key file not written";
			} catch (RuntimeException e) {
				stored = false;
				lastCreateAccountError = describe(e);
			}
			if (!stored) return false;
			lastCreateAccountError = null;
			databaseKey = key;
			loginThrottle().reset();
			return true;
		}
	}

	@Override
	@Nullable
	public String getLastCreateAccountError() {
		return lastCreateAccountError;
	}

	private static String describe(Throwable t) {
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String name = root.getClass().getSimpleName();
		return root == t ? name : t.getClass().getSimpleName() + " (" + name
				+ ")";
	}

	protected static boolean isEmptyPassword(char[] password) {
		if (password == null || password.length == 0) return true;
		for (char c : password) {
			if (!Character.isWhitespace(c)) return false;
		}
		return true;
	}

	@GuardedBy("stateChangeLock")
	private boolean encryptAndStoreDatabaseKey(SecretKey key, char[] password) {
		byte[] plaintext = key.getBytes();
		startStrengthenerGeneration();
		byte[] ciphertext = crypto.encryptWithPassword(plaintext, password,
				databaseConfig.getKeyStrengthener());
		return storeEncryptedDatabaseKey(toHexString(ciphertext));
	}

	@GuardedBy("stateChangeLock")
	protected void startStrengthenerGeneration() {
		KeyStrengthener strengthener = databaseConfig.getKeyStrengthener();
		if (strengthener == null) return;
		try {
			strengthener.startNewGeneration();
		} catch (RuntimeException keepCurrent) {
		}
	}

	@Override
	public void deleteAccount() {
		synchronized (stateChangeLock) {
			loginThrottle().reset();
			IoUtils.deleteFileOrDir(databaseConfig.getDatabaseKeyDirectory());
			IoUtils.deleteFileOrDir(databaseConfig.getDatabaseDirectory());
			if (databaseKey != null) {
				databaseKey.clear();
				databaseKey = null;
			}
			deleteAccountData();
			clearEraseRequest();
		}
	}

	@GuardedBy("stateChangeLock")
	protected void deleteAccountData() {
	}

	@GuardedBy("stateChangeLock")
	private void clearEraseRequest() {
		File marker = eraseMarkerFile();
		if (marker.exists()) marker.delete();
		LoginThrottle.syncDirectory(marker.getAbsoluteFile().getParentFile());
	}

	@Override
	public void shredDatabaseKey() {
		synchronized (stateChangeLock) {
			eraseLocked();
		}
	}

	@Override
	public boolean isEraseRequested() {
		return eraseMarkerFile().exists();
	}

	@GuardedBy("stateChangeLock")
	private void eraseLocked() {
		try {
			LoginThrottle.writeDurably(eraseMarkerFile(), new byte[] {'1'});
		} catch (IOException | RuntimeException ignored) {
		}
		KeyStrengthener strengthener = databaseConfig.getKeyStrengthener();
		if (strengthener != null) {
			try {
				strengthener.discardKeyBeforeFirstAccount();
			} catch (RuntimeException ignored) {
			}
		}
		shredKeyFiles();
		if (databaseKey != null) {
			databaseKey.clear();
			databaseKey = null;
		}
	}

	@GuardedBy("stateChangeLock")
	protected void shredKeyFiles() {
		IoUtils.deleteFileOrDir(databaseConfig.getDatabaseKeyDirectory());
	}

	@Override
	public void signIn(char[] typed) throws DecryptionException {
		PasswordForms password = PasswordForms.of(typed);
		try {
			synchronized (stateChangeLock) {
				checkLockout();
				LoginThrottle.Attempt attempt = beginAttempt();
				try {
					if (databaseKey != null) databaseKey.clear();
					LoadedKey loaded = loadAndDecryptDatabaseKey(password);
					databaseKey = loaded.key;
					resetLockout();
					alignKeyFilesWithPrimaryIfItHolds(loaded.opened);
					retireUnusedStrengthenerGenerations();
				} catch (DecryptionException e) {
					settleFailedAttempt(attempt, e.getDecryptionResult());
					throw e;
				}
			}
		} finally {
			password.clear();
		}
	}

	@Override
	public void verifyPassword(char[] typed) throws DecryptionException {
		PasswordForms password = PasswordForms.of(typed);
		try {
			synchronized (stateChangeLock) {
				verifyPasswordLocked(password);
			}
		} finally {
			password.clear();
		}
	}

	@GuardedBy("stateChangeLock")
	private void verifyPasswordLocked(PasswordForms password)
			throws DecryptionException {
		checkLockout();
		SecretKey loaded = databaseKey;
		if (loaded == null) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
		LoginThrottle.Attempt attempt = beginAttempt();
		byte[] plaintext;
		try {
			plaintext = openStoredKey(password, false).plaintext;
		} catch (DecryptionException e) {
			settleFailedAttempt(attempt, e.getDecryptionResult());
			throw e;
		}
		boolean same = java.security.MessageDigest.isEqual(plaintext,
				loaded.getBytes());
		java.util.Arrays.fill(plaintext, (byte) 0);
		if (!same) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
		resetLockout();
	}

	@GuardedBy("stateChangeLock")
	void settleFailedAttempt(LoginThrottle.Attempt attempt,
			DecryptionResult result) {
		if (result == KEY_FILES_DAMAGED || result == KEY_STRENGTHENER_ERROR) {
			cancelAttempt(attempt);
			return;
		}
		applyErasePolicy(result == INVALID_PASSWORD);
	}

	@GuardedBy("stateChangeLock")
	void applyErasePolicy(boolean passwordRefused) {
		ErasePolicy policy = erasePolicy;
		if (!passwordRefused || policy == null) return;
		if (policy.eraseDue(loginThrottle().failures())) eraseLocked();
	}

	@Nullable
	private LoginThrottle loginThrottle;

	@GuardedBy("stateChangeLock")
	protected LoginThrottle loginThrottle() {
		LoginThrottle t = loginThrottle;
		if (t == null) {
			t = createLoginThrottle(lockoutFile());
			loginThrottle = t;
		}
		return t;
	}

	protected LoginThrottle createLoginThrottle(File stateFile) {
		return LoginThrottle.inFile(stateFile, LoginThrottle.SIGN_IN);
	}

	@GuardedBy("stateChangeLock")
	protected void checkLockout() throws DecryptionException {
		if (loginThrottle().remainingLockoutMs() > 0) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
	}

	@GuardedBy("stateChangeLock")
	protected LoginThrottle.Attempt beginAttempt() {
		return loginThrottle().beginAttempt();
	}

	@GuardedBy("stateChangeLock")
	protected void cancelAttempt(LoginThrottle.Attempt attempt) {
		loginThrottle().cancel(attempt);
	}

	@GuardedBy("stateChangeLock")
	protected void resetLockout() {
		loginThrottle().reset();
	}

	@Override
	public long signInLockoutRemainingMs() {
		synchronized (stateChangeLock) {
			return loginThrottle().remainingLockoutMs();
		}
	}

	@Override
	public int failedSignInAttempts() {
		synchronized (stateChangeLock) {
			return loginThrottle().failures();
		}
	}

	protected void setDatabaseKey(SecretKey key) {
		SecretKey old = this.databaseKey;
		this.databaseKey = key;
		if (old != null && old != key) old.clear();
	}

	@GuardedBy("stateChangeLock")
	private boolean storedKeyDecryptsTo(SecretKey key, char[] password) {
		String hex = readDbKeyFromFile(dbKeyFile());
		if (hex == null) return false;
		byte[] ciphertext;
		try {
			ciphertext = fromHexString(hex);
		} catch (FormatException e) {
			return false;
		}
		byte[] plaintext;
		try {
			plaintext = crypto.decryptWithPassword(ciphertext, password,
					databaseConfig.getKeyStrengthener());
		} catch (DecryptionException | RuntimeException e) {
			return false;
		}
		boolean same = java.util.Arrays.equals(plaintext, key.getBytes());
		java.util.Arrays.fill(plaintext, (byte) 0);
		return same;
	}

	@GuardedBy("stateChangeLock")
	private DecryptionResult settleIncompleteReplacement(SecretKey key,
			String previous, char[] newPassword) {
		writeQuietly(dbKeyFile(), previous);
		if (previous.equals(readDbKeyFromFile(dbKeyFile()))) {
			alignKeyFiles(previous);
			return keyFilesHold(previous) ? KEY_REPLACEMENT_FAILED
					: KEY_REPLACEMENT_UNCERTAIN;
		}
		if (storedKeyDecryptsTo(key, newPassword)) {
			alignKeyFilesWithPrimary();
			return SUCCESS;
		}
		return KEY_REPLACEMENT_UNCERTAIN;
	}

	@GuardedBy("stateChangeLock")
	private boolean keyFilesHold(String hex) {
		return hex.equals(readDbKeyFromFile(dbKeyFile()))
				&& hex.equals(readDbKeyFromFile(dbKeyBackupFile()))
				&& keyState(hex).equals(readDbKeyFromFile(dbKeyStateFile()));
	}

	@GuardedBy("stateChangeLock")
	private void alignKeyFilesWithPrimary() {
		String primary = readDbKeyFromFile(dbKeyFile());
		if (primary != null) alignKeyFiles(primary);
	}

	@GuardedBy("stateChangeLock")
	void alignKeyFilesWithPrimaryIfItHolds(String opened) {
		if (opened.equals(readDbKeyFromFile(dbKeyFile()))) {
			alignKeyFiles(opened);
		}
	}

	@GuardedBy("stateChangeLock")
	private void alignKeyFiles(String hex) {
		if (!hex.equals(readDbKeyFromFile(dbKeyBackupFile()))) {
			writeQuietly(dbKeyBackupFile(), hex);
		}
		String state = keyState(hex);
		if (!state.equals(readDbKeyFromFile(dbKeyStateFile()))) {
			writeQuietly(dbKeyStateFile(), state);
		}
	}

	static final class StoredKey {

		final byte[] plaintext;
		final byte[] ciphertext;
		final String hex;
		final boolean legacyForm;

		StoredKey(byte[] plaintext, byte[] ciphertext, String hex,
				boolean legacyForm) {
			this.plaintext = plaintext;
			this.ciphertext = ciphertext;
			this.hex = hex;
			this.legacyForm = legacyForm;
		}
	}

	private static final class Opened {

		final byte[] plaintext;
		final boolean legacyForm;

		Opened(byte[] plaintext, boolean legacyForm) {
			this.plaintext = plaintext;
			this.legacyForm = legacyForm;
		}
	}

	private Opened decryptWithEitherForm(byte[] ciphertext,
			PasswordForms password, @Nullable KeyStrengthener strengthener)
			throws DecryptionException {
		try {
			return new Opened(crypto.decryptWithPassword(ciphertext,
					password.normal, strengthener), false);
		} catch (DecryptionException e) {
			if (e.getDecryptionResult() != INVALID_PASSWORD
					|| password.legacy == null) {
				throw e;
			}
			return new Opened(crypto.decryptWithPassword(ciphertext,
					password.legacy, strengthener), true);
		}
	}

	@GuardedBy("stateChangeLock")
	StoredKey openStoredKey(PasswordForms password)
			throws DecryptionException {
		return openStoredKey(password, true);
	}

	@GuardedBy("stateChangeLock")
	StoredKey openStoredKey(PasswordForms password, boolean repair)
			throws DecryptionException {
		KeyStrengthener strengthener = databaseConfig.getKeyStrengthener();
		String primary = readDbKeyFromFile(dbKeyFile());
		byte[] primaryCiphertext = parseCiphertext(primary);
		if (primary != null && primaryCiphertext != null) {
			try {
				Opened opened = decryptWithEitherForm(primaryCiphertext,
						password, strengthener);
				return new StoredKey(opened.plaintext, primaryCiphertext,
						primary, opened.legacyForm);
			} catch (DecryptionException e) {
				if (e.getDecryptionResult() != INVALID_CIPHERTEXT) {
					if (e.getDecryptionResult() == INVALID_PASSWORD) {
						StoredKey vouched = openVouchedBackupInsteadOf(
								primary, password, strengthener, repair);
						if (vouched != null) return vouched;
					}
					throw e;
				}
			}
		}
		String backup = readDbKeyFromFile(dbKeyBackupFile());
		if (primary == null && backup == null) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
		String state = readDbKeyFromFile(dbKeyStateFile());
		byte[] backupCiphertext = parseCiphertext(backup);
		if (backup == null || backupCiphertext == null || state == null
				|| !state.equals(keyState(backup))) {
			throw new DecryptionException(KEY_FILES_DAMAGED);
		}
		Opened opened;
		try {
			opened = decryptWithEitherForm(backupCiphertext, password,
					strengthener);
		} catch (DecryptionException e) {
			if (e.getDecryptionResult() == INVALID_CIPHERTEXT) {
				throw new DecryptionException(KEY_FILES_DAMAGED);
			}
			throw e;
		}
		if (repair) writeQuietly(dbKeyFile(), backup);
		return new StoredKey(opened.plaintext, backupCiphertext, backup,
				opened.legacyForm);
	}

	@Nullable
	@GuardedBy("stateChangeLock")
	private StoredKey openVouchedBackupInsteadOf(String primary,
			PasswordForms password, @Nullable KeyStrengthener strengthener,
			boolean repair) {
		String state = readDbKeyFromFile(dbKeyStateFile());
		if (state == null || state.equals(keyState(primary))) return null;
		String backup = readDbKeyFromFile(dbKeyBackupFile());
		byte[] backupCiphertext = parseCiphertext(backup);
		if (backup == null || backupCiphertext == null
				|| !state.equals(keyState(backup))) {
			return null;
		}
		Opened opened;
		try {
			opened = decryptWithEitherForm(backupCiphertext, password,
					strengthener);
		} catch (DecryptionException e) {
			return null;
		}
		if (repair) writeQuietly(dbKeyFile(), backup);
		return new StoredKey(opened.plaintext, backupCiphertext, backup,
				opened.legacyForm);
	}

	@Nullable
	private static byte[] parseCiphertext(@Nullable String hex) {
		if (hex == null || hex.isEmpty()) return null;
		try {
			return fromHexString(hex);
		} catch (FormatException e) {
			return null;
		}
	}

	private static final class LoadedKey {

		final SecretKey key;
		final String opened;

		LoadedKey(SecretKey key, String opened) {
			this.key = key;
			this.opened = opened;
		}
	}

	@GuardedBy("stateChangeLock")
	private LoadedKey loadAndDecryptDatabaseKey(PasswordForms password)
			throws DecryptionException {
		StoredKey stored = openStoredKey(password);
		SecretKey key = new SecretKey(stored.plaintext);
		if (needsReencryption(stored)) {
			try {
				encryptAndStoreDatabaseKey(key, password.normal);
			} catch (org.zerionproject.core.api.crypto
					.KeyStrengthenerException keepExisting) {
			}
		}
		return new LoadedKey(key, stored.hex);
	}

	@GuardedBy("stateChangeLock")
	boolean needsReencryption(StoredKey stored) {
		byte[] ciphertext = stored.ciphertext;
		KeyStrengthener strengthener = databaseConfig.getKeyStrengthener();
		boolean needsStrengthenerUpgrade = false;
		if (strengthener != null) {
			if (!crypto.isEncryptedWithStrengthenedKey(ciphertext)) {
				needsStrengthenerUpgrade = true;
			} else {
				needsStrengthenerUpgrade = strengthener.currentGeneration()
						!= KeyStrengthener.LEGACY_GENERATION
						&& crypto.strengtheningGeneration(ciphertext)
						== KeyStrengthener.LEGACY_GENERATION;
			}
		}
		boolean needsKdfUpgrade = crypto.isEncryptedWithLegacyKdf(ciphertext);
		return stored.legacyForm || needsStrengthenerUpgrade
				|| needsKdfUpgrade;
	}

	@Nullable
	@GuardedBy("stateChangeLock")
	protected Set<Integer> strengtheningGenerationsInUse() {
		Set<Integer> inUse = new HashSet<>();
		if (!addGenerationOf(dbKeyFile(), inUse)) return null;
		if (!addGenerationOf(dbKeyBackupFile(), inUse)) return null;
		return inUse;
	}

	@GuardedBy("stateChangeLock")
	protected boolean addGenerationOf(File keyFile, Set<Integer> inUse) {
		if (!keyFile.exists()) return true;
		String hex = readDbKeyFromFile(keyFile);
		byte[] ciphertext = parseCiphertext(hex);
		if (ciphertext == null) return false;
		int generation = crypto.strengtheningGeneration(ciphertext);
		if (generation >= 0) inUse.add(generation);
		return true;
	}

	@GuardedBy("stateChangeLock")
	protected void retireUnusedStrengthenerGenerations() {
		KeyStrengthener strengthener = databaseConfig.getKeyStrengthener();
		if (strengthener == null || strengthener.currentGeneration()
				== KeyStrengthener.LEGACY_GENERATION) {
			return;
		}
		Set<Integer> inUse = strengtheningGenerationsInUse();
		if (inUse == null) return;
		try {
			strengthener.retainGenerations(inUse);
		} catch (RuntimeException ignored) {
		}
	}

	@Override
	public void changePassword(char[] oldTyped, char[] newTyped)
			throws DecryptionException {
		PasswordForms oldPassword = PasswordForms.of(oldTyped);
		char[] newPassword = PasswordNormalizer.normalize(newTyped);
		try {
			if (isEmptyPassword(newPassword)) {
				throw new IllegalArgumentException(
						"New account password must not be empty");
			}
			synchronized (stateChangeLock) {
				changePasswordLocked(oldPassword, newPassword);
			}
		} finally {
			oldPassword.clear();
			java.util.Arrays.fill(newPassword, '\0');
		}
		java.util.Arrays.fill(oldTyped, '\0');
		java.util.Arrays.fill(newTyped, '\0');
	}

	@GuardedBy("stateChangeLock")
	private void changePasswordLocked(PasswordForms oldPassword,
			char[] newPassword) throws DecryptionException {
		checkLockout();
		LoginThrottle.Attempt attempt = beginAttempt();
		LoadedKey loaded;
		try {
			loaded = loadAndDecryptDatabaseKey(oldPassword);
		} catch (DecryptionException e) {
			settleFailedAttempt(attempt, e.getDecryptionResult());
			throw e;
		}
		SecretKey key = loaded.key;
		resetLockout();
		boolean replaced;
		try {
			replaced = encryptAndStoreDatabaseKey(key, newPassword)
					&& storedKeyDecryptsTo(key, newPassword);
		} catch (KeyStrengthenerException e) {
			if (databaseKey == null) key.clear();
			throw new DecryptionException(KEY_STRENGTHENER_ERROR);
		} catch (RuntimeException e) {
			replaced = false;
		}
		if (!replaced) {
			DecryptionResult outcome = settleIncompleteReplacement(key,
					loaded.opened, newPassword);
			if (outcome != SUCCESS) {
				if (databaseKey == null) key.clear();
				throw new DecryptionException(outcome);
			}
		}
		retireUnusedStrengthenerGenerations();
		if (databaseKey == null) {
			databaseKey = key;
		} else {
			key.clear();
		}
	}
}
