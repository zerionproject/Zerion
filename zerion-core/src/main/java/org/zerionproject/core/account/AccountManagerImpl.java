package org.zerionproject.core.account;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
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
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_CIPHERTEXT;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class AccountManagerImpl implements AccountManager, Service {
	private static final String DB_KEY_FILENAME = "db.key";
	private static final String DB_KEY_BACKUP_FILENAME = "db.key.bak";
	private static final String LOCKOUT_FILENAME = "login.lockout";

	protected final DatabaseConfig databaseConfig;
	protected final CryptoComponent crypto;
	protected final IdentityManager identityManager;

	final Object stateChangeLock = new Object();

	@Nullable
	private volatile SecretKey databaseKey = null;

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

	protected File lockoutFile() {
		return new File(databaseConfig.getDatabaseKeyDirectory(),
				LOCKOUT_FILENAME);
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

	/**
	 * Writes the key to the primary file and then to the backup, each through
	 * a synced temporary file and an atomic rename, so at every instant at
	 * least one of the two files holds a complete key: the primary is
	 * replaced in one step and the old backup is left untouched until the
	 * new primary is durable.
	 */
	@GuardedBy("stateChangeLock")
	boolean storeEncryptedDatabaseKey(String hex) {
		databaseConfig.getDatabaseKeyDirectory().mkdirs();
		File dbKeyFile = dbKeyFile();
		File dbKeyBackupFile = dbKeyBackupFile();
		byte[] bytes = hex.getBytes(UTF_8);
		try {
			LoginThrottle.writeDurably(dbKeyFile, bytes);
			LoginThrottle.writeDurably(dbKeyBackupFile, bytes);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public boolean accountExists() {
		synchronized (stateChangeLock) {
			return loadEncryptedDatabaseKey() != null;
		}
	}

	@Override
	public boolean createAccount(String name, char[] password) {
		synchronized (stateChangeLock) {
			if (hasDatabaseKey())
				throw new AssertionError("Already have a database key");
			if (isEmptyPassword(password)) return false;
			Identity identity = identityManager.createIdentity(name);
			identityManager.registerIdentity(identity);
			SecretKey key = crypto.generateSecretKey();
			boolean stored;
			try {
				stored = encryptAndStoreDatabaseKey(key, password);
			} catch (RuntimeException e) {
				stored = false;
			}
			if (!stored) return false;
			databaseKey = key;
			loginThrottle().reset();
			return true;
		}
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
		byte[] ciphertext = crypto.encryptWithPassword(plaintext, password,
				databaseConfig.getKeyStrengthener());
		return storeEncryptedDatabaseKey(toHexString(ciphertext));
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
		}
	}

	@Override
	public void signIn(char[] password) throws DecryptionException {
		synchronized (stateChangeLock) {
			checkLockout();
			try {
				if (databaseKey != null) databaseKey.clear();
				databaseKey = loadAndDecryptDatabaseKey(password);
				resetLockout();
			} catch (DecryptionException e) {
				recordFailedAttempt();
				throw e;
			}
		}
	}

	@Nullable
	private LoginThrottle loginThrottle;

	/** The single failed-attempt throttle for this account; created lazily. */
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
	protected void recordFailedAttempt() {
		loginThrottle().recordFailure();
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
	private SecretKey loadAndDecryptDatabaseKey(char[] password)
			throws DecryptionException {
		String hex = loadEncryptedDatabaseKey();
		if (hex == null) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
		byte[] ciphertext;
		try {
			ciphertext = fromHexString(hex);
		} catch (FormatException e) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
		KeyStrengthener keyStrengthener = databaseConfig.getKeyStrengthener();
		byte[] plaintext = crypto.decryptWithPassword(ciphertext, password,
				keyStrengthener);
		SecretKey key = new SecretKey(plaintext);
		boolean needsStrengthenerUpgrade = keyStrengthener != null &&
				!crypto.isEncryptedWithStrengthenedKey(ciphertext);
		boolean needsKdfUpgrade = crypto.isEncryptedWithLegacyKdf(ciphertext);
		if (needsStrengthenerUpgrade || needsKdfUpgrade) {
			try {
				encryptAndStoreDatabaseKey(key, password);
			} catch (org.zerionproject.core.api.crypto
					.KeyStrengthenerException keepExisting) {
			}
		}
		return key;
	}

	@Override
	public void changePassword(char[] oldPassword, char[] newPassword)
			throws DecryptionException {
		if (isEmptyPassword(newPassword)) {
			throw new IllegalArgumentException(
					"New account password must not be empty");
		}
		synchronized (stateChangeLock) {
			SecretKey key = loadAndDecryptDatabaseKey(oldPassword);
			try {
				encryptAndStoreDatabaseKey(key, newPassword);
			} catch (org.zerionproject.core.api.crypto
					.KeyStrengthenerException e) {
				throw new DecryptionException(org.zerionproject.core.api
						.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR);
			}
			if (databaseKey == null) {
				databaseKey = key;
			} else {
				key.clear();
			}
		}
		java.util.Arrays.fill(oldPassword, '\0');
		java.util.Arrays.fill(newPassword, '\0');
	}
}
