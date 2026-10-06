package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.utils.SecureMemory;

import android.app.Application;

import org.zerionproject.core.account.AndroidAccountManager;
import org.zerionproject.core.account.PasswordNormalizer;
import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.transport.RootKeyStore;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

import javax.inject.Inject;

import static com.professor.zerion.android.backup.BackupException.Reason.IMPORT_FAILED;
import static com.professor.zerion.android.backup.BackupException.Reason.IO_ERROR;
import static com.professor.zerion.android.backup.BackupException.Reason.NOT_SIGNED_IN;

@NotNullByDefault
public class AccountBackupManager {

	private static final int MAX_DB_BYTES = 512 * 1024 * 1024;

	static final long ROOT_EVOLUTION_PAUSE_MS = 7L * 24 * 60 * 60 * 1000;

	private final Application app;
	private final DatabaseComponent db;
	private final AndroidAccountManager accountManager;
	private final ProfileManager profileManager;
	private final IdentityManager identityManager;
	private final RootKeyStore rootKeyStore;
	private final BackupCrypto backupCrypto = new BackupCrypto();

	@Inject
	AccountBackupManager(Application app, DatabaseComponent db,
			AndroidAccountManager accountManager,
			ProfileManager profileManager, IdentityManager identityManager,
			RootKeyStore rootKeyStore) {
		this.app = app;
		this.db = db;
		this.accountManager = accountManager;
		this.profileManager = profileManager;
		this.identityManager = identityManager;
		this.rootKeyStore = rootKeyStore;
	}

	public byte[] exportAccount(char[] passphrase) throws BackupException {
		byte[] bundleBytes = snapshotBundle();
		char[] normal = PasswordNormalizer.normalize(passphrase);
		try {
			return backupCrypto.seal(bundleBytes, normal, (byte) 0,
					argon2Params());
		} finally {
			Arrays.fill(bundleBytes, (byte) 0);
			Arrays.fill(normal, '\0');
		}
	}

	private com.professor.zerion.android.vault.crypto.Argon2.Argon2Params
			argon2Params() {
		return com.professor.zerion.android.vault.crypto.Argon2.Argon2Params
				.getBackupStrong();
	}

	public void importAccount(byte[] fileBytes, char[] passphrase,
			char[] newPassword) throws BackupException {
		BackupCrypto.Opened opened = openWithEitherForm(fileBytes, passphrase);
		try {
			provisionFromBundle(opened.bundle, newPassword);
		} finally {
			Arrays.fill(opened.bundle, (byte) 0);
		}
	}

	BackupCrypto.Opened openWithEitherForm(byte[] fileBytes,
			char[] passphrase) throws BackupException {
		char[] normal = PasswordNormalizer.normalize(passphrase);
		char[] legacy = PasswordNormalizer.legacyForm(passphrase, normal);
		try {
			try {
				return backupCrypto.open(fileBytes, normal);
			} catch (BackupException e) {
				if (legacy == null || e.reason
						!= BackupException.Reason.WRONG_PASSPHRASE) {
					throw e;
				}
				return backupCrypto.open(fileBytes, legacy);
			}
		} finally {
			Arrays.fill(normal, '\0');
			if (legacy != null) Arrays.fill(legacy, '\0');
		}
	}

	byte[] snapshotBundle() throws BackupException {
		SecretKey key = accountManager.getDatabaseKey();
		if (key == null) throw new BackupException(NOT_SIGNED_IN);
		byte[] dbKey = key.getBytes().clone();
		File snapshot = new File(app.getCacheDir(),
				"zbk-" + UUID.randomUUID() + ".tmp");
		byte[] dbBytes = null;
		rootKeyStore.beginSnapshot();
		try {
			writeSnapshot(snapshot);
			dbBytes = readFile(snapshot);
			String name = profileManager.readDisplayName(
					profileManager.getActiveProfileId());
			if (name == null || name.isEmpty()) {
				name = identityManager.getLocalAuthor().getName();
			}
			BackupBundle bundle = new BackupBundle(name, dbKey, dbBytes, null);
			pauseRootEvolution();
			return bundle.toBytes();
		} catch (IOException | DbException | SQLException e) {
			throw new BackupException(IO_ERROR);
		} finally {
			rootKeyStore.endSnapshot();
			if (dbBytes != null) Arrays.fill(dbBytes, (byte) 0);
			Arrays.fill(dbKey, (byte) 0);
			secureDelete(snapshot);
		}
	}

	private void pauseRootEvolution() throws DbException {
		long until = System.currentTimeMillis() + ROOT_EVOLUTION_PAUSE_MS;
		db.transaction(false, txn -> rootKeyStore.pauseUntil(txn, until));
	}

	void provisionFromBundle(byte[] bundleBytes, char[] newPassword)
			throws BackupException {
		BackupBundle bundle = BackupBundle.fromBytes(bundleBytes);
		try {
			String id = accountManager.importProfile(bundle.displayName,
					newPassword, bundle.dbFile, bundle.dbKey);
			if (id == null) throw new BackupException(IMPORT_FAILED);
		} finally {
			bundle.clear();
		}
	}

	private void writeSnapshot(File out)
			throws DbException, SQLException, IOException {
		if (out.exists() && !out.delete()) {
			throw new IOException("Cannot clear snapshot target");
		}
		String target = out.getAbsolutePath().replace("'", "''");
		db.transaction(true, txn -> {
			Connection c = (Connection) txn.unbox();
			c.setAutoCommit(true);
			try (Statement s = c.createStatement()) {
				s.execute("VACUUM INTO '" + target + "'");
			} finally {
				c.setAutoCommit(false);
			}
		});
		try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
			raf.getFD().sync();
		}
	}

	private byte[] readFile(File f) throws IOException {
		long len = f.length();
		if (len <= 0 || len > MAX_DB_BYTES) {
			throw new IOException("Snapshot empty or too large");
		}
		byte[] data = new byte[(int) len];
		try (FileInputStream in = new FileInputStream(f)) {
			int off = 0;
			while (off < data.length) {
				int r = in.read(data, off, data.length - off);
				if (r < 0) break;
				off += r;
			}
			if (off != data.length) throw new IOException("Short read");
		}
		return data;
	}

	private void secureDelete(File f) {
		SecureMemory.secureDeleteFile(f, MAX_DB_BYTES, false);
	}
}
