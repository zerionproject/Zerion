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
import java.io.FileOutputStream;
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
	private static final int MAX_FAILED_ATTEMPTS = 10;
	private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;

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

	@GuardedBy("stateChangeLock")
	boolean storeEncryptedDatabaseKey(String hex) {
		databaseConfig.getDatabaseKeyDirectory().mkdirs();
		File dbKeyFile = dbKeyFile();
		File dbKeyBackupFile = dbKeyBackupFile();
		if (dbKeyBackupFile.exists() && !dbKeyFile.exists()) {
			dbKeyBackupFile.renameTo(dbKeyFile);
		}
		try {
			writeDbKeyToFile(hex, dbKeyBackupFile);
			if (dbKeyFile.exists()) {
				dbKeyFile.delete();
			}
			if (!dbKeyBackupFile.renameTo(dbKeyFile)) {
				return false;
			}
			writeDbKeyToFile(hex, dbKeyBackupFile);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	@GuardedBy("stateChangeLock")
	private void writeDbKeyToFile(String key, File f) throws IOException {
		FileOutputStream out = new FileOutputStream(f);
		out.write(key.getBytes(UTF_8));
		out.flush();
		out.close();
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
			Identity identity = identityManager.createIdentity(name);
			identityManager.registerIdentity(identity);
			SecretKey key = crypto.generateSecretKey();
			if (!encryptAndStoreDatabaseKey(key, password)) return false;
			databaseKey = key;
			return true;
		}
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

	@GuardedBy("stateChangeLock")
	protected void checkLockout() throws DecryptionException {
		File lockoutFile = lockoutFile();
		if (!lockoutFile.exists()) return;
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					new FileInputStream(lockoutFile), UTF_8));
			String line = reader.readLine();
			reader.close();
			if (line == null) return;
			String[] parts = line.split(",");
			if (parts.length != 2) return;
			int attempts = Integer.parseInt(parts[0]);
			long lastFailTime = Long.parseLong(parts[1]);
			if (attempts >= MAX_FAILED_ATTEMPTS) {
				long elapsed = System.currentTimeMillis() - lastFailTime;
				if (elapsed < LOCKOUT_DURATION_MS) {
					throw new DecryptionException(INVALID_CIPHERTEXT);
				}
					resetLockout();
			}
		} catch (IOException | NumberFormatException e) {
			lockoutFile.delete();
		}
	}

	@GuardedBy("stateChangeLock")
	protected void recordFailedAttempt() {
		File lockoutFile = lockoutFile();
		int attempts = 0;
		if (lockoutFile.exists()) {
			try {
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(
								new FileInputStream(lockoutFile), UTF_8));
				String line = reader.readLine();
				reader.close();
				if (line != null) {
					String[] parts = line.split(",");
					if (parts.length == 2) {
						attempts = Integer.parseInt(parts[0]);
					}
				}
			} catch (IOException | NumberFormatException e) {
			}
		}
		attempts++;
		try {
			FileOutputStream out = new FileOutputStream(lockoutFile);
			String data = attempts + "," + System.currentTimeMillis();
			out.write(data.getBytes(UTF_8));
			out.flush();
			out.close();
		} catch (IOException e) {
		}
	}

	@GuardedBy("stateChangeLock")
	protected void resetLockout() {
		File lockoutFile = lockoutFile();
		if (lockoutFile.exists()) {
			lockoutFile.delete();
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
			encryptAndStoreDatabaseKey(key, password);
		}
		return key;
	}

	@Override
	public void changePassword(char[] oldPassword, char[] newPassword)
			throws DecryptionException {
		synchronized (stateChangeLock) {
			SecretKey key = loadAndDecryptDatabaseKey(oldPassword);
			encryptAndStoreDatabaseKey(key, newPassword);
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
