package com.professor.zerion.android.backup;

import android.app.Application;

import org.briarproject.bramble.account.AndroidAccountManager;
import org.briarproject.bramble.account.ProfileManager;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.identity.IdentityManager;
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

	private final Application app;
	private final DatabaseComponent db;
	private final AndroidAccountManager accountManager;
	private final ProfileManager profileManager;
	private final IdentityManager identityManager;
	private final BackupCrypto backupCrypto = new BackupCrypto();

	@Inject
	AccountBackupManager(Application app, DatabaseComponent db,
			AndroidAccountManager accountManager,
			ProfileManager profileManager, IdentityManager identityManager) {
		this.app = app;
		this.db = db;
		this.accountManager = accountManager;
		this.profileManager = profileManager;
		this.identityManager = identityManager;
	}

	public byte[] exportAccount(char[] passphrase) throws BackupException {
		byte[] bundleBytes = snapshotBundle();
		try {
			return backupCrypto.seal(bundleBytes, passphrase, (byte) 0,
					argon2Params());
		} finally {
			Arrays.fill(bundleBytes, (byte) 0);
		}
	}

	private com.professor.zerion.android.vault.crypto.Argon2.Argon2Params
			argon2Params() {
		return com.professor.zerion.android.vault.crypto.Argon2.Argon2Params
				.getBackupStrong();
	}

	public void importAccount(byte[] fileBytes, char[] passphrase,
			char[] newPassword) throws BackupException {
		BackupCrypto.Opened opened = backupCrypto.open(fileBytes, passphrase);
		try {
			provisionFromBundle(opened.bundle, newPassword);
		} finally {
			Arrays.fill(opened.bundle, (byte) 0);
		}
	}

	byte[] snapshotBundle() throws BackupException {
		SecretKey key = accountManager.getDatabaseKey();
		if (key == null) throw new BackupException(NOT_SIGNED_IN);
		byte[] dbKey = key.getBytes().clone();
		File snapshot = new File(app.getCacheDir(),
				"zbk-" + UUID.randomUUID() + ".tmp");
		byte[] dbBytes = null;
		try {
			writeSnapshot(snapshot);
			dbBytes = readFile(snapshot);
			String name = profileManager.readDisplayName(
					profileManager.getActiveProfileId());
			if (name == null || name.isEmpty()) {
				name = identityManager.getLocalAuthor().getName();
			}
			BackupBundle bundle = new BackupBundle(name, dbKey, dbBytes, null);
			return bundle.toBytes();
		} catch (IOException | DbException | SQLException e) {
			throw new BackupException(IO_ERROR);
		} finally {
			if (dbBytes != null) Arrays.fill(dbBytes, (byte) 0);
			Arrays.fill(dbKey, (byte) 0);
			secureDelete(snapshot);
		}
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
		if (!f.exists()) return;
		try {
			long len = f.length();
			if (len > 0 && len < MAX_DB_BYTES) {
				try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
					byte[] zeroes = new byte[8192];
					long written = 0;
					while (written < len) {
						int chunk = (int) Math.min(zeroes.length, len - written);
						raf.write(zeroes, 0, chunk);
						written += chunk;
					}
					raf.getFD().sync();
				}
			}
		} catch (IOException ignored) {
		}
		f.delete();
	}
}
