package com.professor.zerion.android.backup;

import com.professor.zerion.android.util.CacheSweeper;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.utils.SecureMemory;

import android.app.Application;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.zerionproject.core.account.AndroidAccountManager;
import org.zerionproject.core.account.PasswordNormalizer;
import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.transport.RootKeyStore;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.SequenceInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.IMPORT_FAILED;
import static com.professor.zerion.android.backup.BackupException.Reason.IO_ERROR;
import static com.professor.zerion.android.backup.BackupException.Reason.NOT_A_BACKUP;
import static com.professor.zerion.android.backup.BackupException.Reason.NOT_SIGNED_IN;
import static com.professor.zerion.android.backup.BackupException.Reason.UNSUPPORTED_VERSION;
import static com.professor.zerion.android.backup.BackupException.Reason.WRONG_PASSPHRASE;

@NotNullByDefault
public class AccountBackupManager {

	static final long MAX_DB_BYTES = Integer.MAX_VALUE - (1L << 20);

	static final long ROOT_EVOLUTION_PAUSE_MS = 7L * 24 * 60 * 60 * 1000;

	private static final int PROBE_BYTES = 5;

	public interface CopyWriter {
		void write(InputStream bundle, long length)
				throws IOException, BackupException;
	}

	public interface Destination {
		OutputStream open() throws IOException;

		default void discard() {
		}
	}

	interface Sealer {
		void seal(InputStream bundle, long length, char[] passphrase,
				OutputStream out) throws IOException;
	}

	private static final class Copy implements RunningCopies.Abort {

		private final File snapshot;
		private final Runnable onAbort;
		private volatile boolean aborted = false;
		@Nullable
		private volatile Closeable source;

		private Copy(File snapshot, Runnable onAbort) {
			this.snapshot = snapshot;
			this.onAbort = onAbort;
		}

		@Override
		public void abort() {
			aborted = true;
			closeQuietly(source);
			try {
				onAbort.run();
			} finally {
				SecureMemory.secureDeleteFile(snapshot, 0L, false);
			}
		}

		private void check() throws IOException {
			if (aborted) throw new IOException("Copy aborted");
		}
	}

	private final Application app;
	private final DatabaseComponent db;
	private final AndroidAccountManager accountManager;
	private final ProfileManager profileManager;
	private final IdentityManager identityManager;
	private final RootKeyStore rootKeyStore;
	private final BackupCrypto backupCrypto;
	private final Sealer sealer;

	@Inject
	AccountBackupManager(Application app, DatabaseComponent db,
			AndroidAccountManager accountManager,
			ProfileManager profileManager, IdentityManager identityManager,
			RootKeyStore rootKeyStore) {
		this(app, db, accountManager, profileManager, identityManager,
				rootKeyStore, new BackupCrypto(),
				sealer(Argon2.Argon2Params.getBackupStrong(),
						BackupStream.DEFAULT_CHUNK));
	}

	AccountBackupManager(Application app, DatabaseComponent db,
			AndroidAccountManager accountManager,
			ProfileManager profileManager, IdentityManager identityManager,
			RootKeyStore rootKeyStore, BackupCrypto backupCrypto,
			Sealer sealer) {
		this.app = app;
		this.db = db;
		this.accountManager = accountManager;
		this.profileManager = profileManager;
		this.identityManager = identityManager;
		this.rootKeyStore = rootKeyStore;
		this.backupCrypto = backupCrypto;
		this.sealer = sealer;
	}

	static Sealer sealer(Argon2.Argon2Params params, int chunkSize) {
		return (bundle, length, passphrase, out) -> BackupStream.seal(bundle,
				length, passphrase, (byte) 0, params, chunkSize, out);
	}

	public void exportAccount(char[] passphrase, Destination destination)
			throws BackupException {
		char[] normal = PasswordNormalizer.normalize(passphrase);
		AtomicReference<OutputStream> opened = new AtomicReference<>();
		try {
			copyAccount((bundle, length) -> {
				try (OutputStream out = destination.open()) {
					opened.set(out);
					sealer.seal(bundle, length, normal, out);
					out.flush();
				}
			}, () -> {
				closeQuietly(opened.get());
				destination.discard();
			});
		} finally {
			Arrays.fill(normal, '\0');
		}
	}

	public void importBackupFile(Uri uri, char[] passphrase,
			char[] newPassword) throws BackupException {
		ParcelFileDescriptor pfd;
		try {
			pfd = app.getContentResolver().openFileDescriptor(uri, "r");
		} catch (IOException | SecurityException | IllegalArgumentException e) {
			throw new BackupException(IO_ERROR);
		}
		if (pfd == null) throw new BackupException(IO_ERROR);
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
		try {
			importAccount(in, pfd.getStatSize(), passphrase, newPassword);
		} catch (IOException | SecurityException e) {
			throw new BackupException(IO_ERROR);
		} finally {
			try {
				in.close();
			} catch (IOException ignored) {
			}
		}
	}

	public void importAccount(byte[] fileBytes, char[] passphrase,
			char[] newPassword) throws BackupException {
		try {
			importAccount(new ByteArrayInputStream(fileBytes),
					fileBytes.length, passphrase, newPassword);
		} catch (IOException e) {
			throw new BackupException(IO_ERROR);
		}
	}

	void importAccount(InputStream source, long declaredLength,
			char[] passphrase, char[] newPassword)
			throws BackupException, IOException {
		BufferedInputStream in = new BufferedInputStream(source, 1 << 16);
		in.mark(PROBE_BYTES);
		byte[] probe = new byte[PROBE_BYTES];
		int got = 0;
		while (got < PROBE_BYTES) {
			int r = in.read(probe, got, PROBE_BYTES - got);
			if (r < 0) break;
			got += r;
		}
		in.reset();
		if (got < BackupStream.MAGIC.length || !Arrays.equals(
				Arrays.copyOf(probe, BackupStream.MAGIC.length),
				BackupStream.MAGIC)) {
			throw new BackupException(NOT_A_BACKUP);
		}
		if (got < PROBE_BYTES) throw new BackupException(CORRUPT);
		byte version = probe[PROBE_BYTES - 1];
		if (version == BackupStream.VERSION) {
			importStream(in, passphrase, newPassword);
		} else if (version == 1 || version == 2) {
			importLegacy(BackupMemory.read(in, declaredLength,
					BackupMemory.limit()), passphrase, newPassword);
		} else {
			throw new BackupException(UNSUPPORTED_VERSION);
		}
	}

	private void importLegacy(byte[] fileBytes, char[] passphrase,
			char[] newPassword) throws BackupException {
		try {
			BackupMemory.check(fileBytes.length);
			BackupCrypto.Opened opened =
					openWithEitherForm(fileBytes, passphrase);
			try {
				provisionFromBundle(opened.bundle, newPassword);
			} finally {
				Arrays.fill(opened.bundle, (byte) 0);
			}
		} finally {
			Arrays.fill(fileBytes, (byte) 0);
		}
	}

	private void importStream(InputStream in, char[] passphrase,
			char[] newPassword) throws BackupException, IOException {
		byte[] encoded = new byte[BackupStream.HEADER_LENGTH];
		byte[] mac = new byte[BackupStream.MAC_LENGTH];
		if (!BackupStream.readFully(in, encoded, encoded.length)
				|| !BackupStream.readFully(in, mac, mac.length)) {
			throw new BackupException(CORRUPT);
		}
		BackupStream.Header header = BackupStream.parse(encoded);
		BackupStream.Keys keys = unlock(header, mac, passphrase);
		File temp = CacheSweeper.claim(new File(app.getCacheDir(),
				"zbk-" + UUID.randomUUID() + ".restore"));
		BundleStreamParser parser = null;
		byte[] dbKey = null;
		try {
			try (FileOutputStream file = new FileOutputStream(temp)) {
				parser = new BundleStreamParser(file);
				BackupStream.readChunks(in, header, keys.enc, parser);
				parser.finish();
				file.getFD().sync();
			}
			dbKey = parser.dbKey();
			String id = accountManager.importProfile(parser.displayName(),
					newPassword, temp, dbKey);
			if (id == null) throw new BackupException(IMPORT_FAILED);
		} catch (BundleStreamParser.Malformed e) {
			throw new BackupException(e.reason);
		} finally {
			keys.clear();
			if (parser != null) parser.wipe();
			if (dbKey != null) Arrays.fill(dbKey, (byte) 0);
			secureDelete(temp);
			CacheSweeper.release(temp);
		}
	}

	private BackupStream.Keys unlock(BackupStream.Header header, byte[] mac,
			char[] passphrase) throws BackupException {
		char[] normal = PasswordNormalizer.normalize(passphrase);
		char[] legacy = PasswordNormalizer.legacyForm(passphrase, normal);
		try {
			BackupStream.Keys keys = BackupStream.derive(normal, header);
			if (BackupStream.verify(keys, mac)) return keys;
			keys.clear();
			if (legacy != null) {
				keys = BackupStream.derive(legacy, header);
				if (BackupStream.verify(keys, mac)) return keys;
				keys.clear();
			}
			throw new BackupException(WRONG_PASSPHRASE);
		} finally {
			Arrays.fill(normal, '\0');
			if (legacy != null) Arrays.fill(legacy, '\0');
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

	public void copyAccount(CopyWriter writer) throws BackupException {
		copyAccount(writer, () -> {
		});
	}

	private void copyAccount(CopyWriter writer, Runnable onAbort)
			throws BackupException {
		SecretKey key = accountManager.getDatabaseKey();
		if (key == null) throw new BackupException(NOT_SIGNED_IN);
		byte[] dbKey = key.getBytes().clone();
		File snapshot = CacheSweeper.claim(new File(app.getCacheDir(),
				"zbk-" + UUID.randomUUID() + ".tmp"));
		byte[] prefix = null;
		Copy copy = new Copy(snapshot, onAbort);
		RunningCopies.start(copy);
		rootKeyStore.beginSnapshot();
		try {
			writeSnapshot(snapshot);
			copy.check();
			long length = snapshot.length();
			if (length <= 0 || length > MAX_DB_BYTES) {
				throw new IOException("Snapshot empty or too large");
			}
			prefix = BackupBundle.prefix(displayName(), dbKey, length);
			byte[] suffix = BackupBundle.suffix();
			try (InputStream file = new FileInputStream(snapshot);
					InputStream bundle = new SequenceInputStream(
							new SequenceInputStream(
									new ByteArrayInputStream(prefix), file),
							new ByteArrayInputStream(suffix))) {
				copy.source = file;
				copy.check();
				writer.write(bundle, prefix.length + length + suffix.length);
			}
			copy.check();
			pauseRootEvolution();
		} catch (IOException | DbException | SQLException e) {
			throw new BackupException(IO_ERROR);
		} finally {
			RunningCopies.end(copy);
			rootKeyStore.endSnapshot();
			if (prefix != null) Arrays.fill(prefix, (byte) 0);
			Arrays.fill(dbKey, (byte) 0);
			secureDelete(snapshot);
			CacheSweeper.release(snapshot);
		}
	}

	private String displayName() throws DbException {
		String name = profileManager.readDisplayName(
				profileManager.getActiveProfileId());
		if (name == null || name.isEmpty()) {
			name = identityManager.getLocalAuthor().getName();
		}
		return name;
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

	private void secureDelete(@Nullable File f) {
		if (f != null) SecureMemory.secureDeleteFile(f, 0L, false);
	}

	private static void closeQuietly(@Nullable Closeable c) {
		if (c == null) return;
		try {
			c.close();
		} catch (IOException ignored) {
		}
	}
}
