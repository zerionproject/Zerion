package com.professor.zerion.android.backup;

import android.app.Application;

import com.professor.zerion.android.util.CacheSweeper;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.account.AndroidAccountManager;
import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.transport.RootKeyStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.backup.BackupBundleParserTest.EARLIER_BUNDLE;
import static com.professor.zerion.android.backup.BackupBundleParserTest.hex;
import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.NOT_A_BACKUP;
import static com.professor.zerion.android.backup.BackupException.Reason.TOO_LARGE;
import static com.professor.zerion.android.backup.BackupException.Reason.UNSUPPORTED_VERSION;
import static com.professor.zerion.android.backup.BackupException.Reason.WRONG_PASSPHRASE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BackupImportTest {

	private static final Argon2.Argon2Params FAST =
			new Argon2.Argon2Params(Argon2.LOW_MEMORY_KB, 1, 1,
					Argon2.DEFAULT_HASH_LENGTH);
	private static final char[] PASSPHRASE = "a backup passphrase".toCharArray();
	private static final char[] NEW_PASSWORD = "new device pass".toCharArray();

	private final Application app = ApplicationProvider.getApplicationContext();
	private final AndroidAccountManager accounts =
			mock(AndroidAccountManager.class);
	private final List<byte[]> restored = new ArrayList<>();
	private final List<String> names = new ArrayList<>();
	private final List<File> movedFrom = new ArrayList<>();

	@After
	public void nothingIsLeftInTheCache() {
		File[] left = app.getCacheDir().listFiles(
				(dir, n) -> n.startsWith("zbk-"));
		assertTrue(left == null || left.length == 0);
	}

	private AccountBackupManager manager() {
		when(accounts.importProfile(anyString(), any(char[].class),
				any(File.class), any(byte[].class))).thenAnswer(inv -> {
					File f = inv.getArgument(2);
					names.add(inv.getArgument(0));
					movedFrom.add(f);
					restored.add(Files.readAllBytes(f.toPath()));
					assertTrue(f.delete());
					return "profile";
				});
		when(accounts.importProfile(anyString(), any(char[].class),
				any(byte[].class), any(byte[].class))).thenAnswer(inv -> {
					names.add(inv.getArgument(0));
					restored.add(((byte[]) inv.getArgument(2)).clone());
					return "profile";
				});
		return new AccountBackupManager(app, mock(DatabaseComponent.class),
				accounts, mock(ProfileManager.class),
				mock(IdentityManager.class),
				new RootKeyStore(mock(DatabaseComponent.class)),
				new BackupCrypto(),
				AccountBackupManager.sealer(FAST, BackupStream.MIN_CHUNK));
	}

	private static byte[] v3(String name, byte[] db, char[] passphrase)
			throws IOException {
		byte[] key = new byte[32];
		Arrays.fill(key, (byte) 3);
		byte[] prefix = BackupBundle.prefix(name, key, db.length);
		byte[] suffix = BackupBundle.suffix();
		ByteArrayOutputStream bundle = new ByteArrayOutputStream();
		bundle.write(prefix);
		bundle.write(db);
		bundle.write(suffix);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AccountBackupManager.sealer(FAST, BackupStream.MIN_CHUNK).seal(
				new ByteArrayInputStream(bundle.toByteArray()), bundle.size(),
				passphrase, out);
		return out.toByteArray();
	}

	private static byte[] database(int size) {
		byte[] b = new byte[size];
		new Random(size).nextBytes(b);
		return b;
	}

	private void assertRefused(byte[] file, BackupException.Reason reason)
			throws IOException {
		try {
			manager().importAccount(new ByteArrayInputStream(file),
					file.length, PASSPHRASE.clone(), NEW_PASSWORD.clone());
			fail("expected " + reason);
		} catch (BackupException e) {
			assertEquals(reason, e.reason);
		}
		assertTrue("a profile was created from a refused file",
				restored.isEmpty());
	}

	@Test
	public void aVersionThreeBackupIsRestoredThroughATemporaryFile()
			throws Exception {
		byte[] db = database(3 * BackupStream.MIN_CHUNK + 123);
		byte[] file = v3("Alice", db, PASSPHRASE);
		assertEquals(3, file[4]);
		manager().importAccount(new ByteArrayInputStream(file), file.length,
				PASSPHRASE.clone(), NEW_PASSWORD.clone());
		assertEquals(1, restored.size());
		assertEquals("Alice", names.get(0));
		assertArrayEquals(db, restored.get(0));
		assertTrue(movedFrom.get(0).getName().startsWith("zbk-"));
		assertEquals(app.getCacheDir(), movedFrom.get(0).getParentFile());
	}

	@Test
	public void theAppLockingDuringARestoreLeavesItsTemporaryFileAlone()
			throws Exception {
		byte[] db = database(6 * BackupStream.MIN_CHUNK + 77);
		byte[] file = v3("Alice", db, PASSPHRASE);
		InputStream lockHalfway = new FilterInputStream(
				new ByteArrayInputStream(file)) {
			private int read;
			private boolean swept;

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				int n = super.read(b, off, Math.min(len, 1024));
				if (n > 0) read += n;
				if (!swept && read > file.length / 2) {
					swept = true;
					CacheSweeper.sweep(app);
				}
				return n;
			}
		};
		manager().importAccount(lockHalfway, file.length, PASSPHRASE.clone(),
				NEW_PASSWORD.clone());
		assertEquals(1, restored.size());
		assertArrayEquals(db, restored.get(0));
	}

	@Test
	public void anExportOpensAgain() throws Exception {
		byte[] db = database(BackupStream.MIN_CHUNK * 2);
		byte[] key = new byte[32];
		byte[] prefix = BackupBundle.prefix("Bob", key, db.length);
		ByteArrayOutputStream saved = new ByteArrayOutputStream();
		AccountBackupManager.sealer(FAST, BackupStream.DEFAULT_CHUNK).seal(
				new java.io.SequenceInputStream(new java.io.SequenceInputStream(
						new ByteArrayInputStream(prefix),
						new ByteArrayInputStream(db)),
						new ByteArrayInputStream(BackupBundle.suffix())),
				prefix.length + db.length + 4, PASSPHRASE, saved);
		manager().importAccount(saved.toByteArray(), PASSPHRASE.clone(),
				NEW_PASSWORD.clone());
		assertArrayEquals(db, restored.get(0));
	}

	@Test
	public void aTruncatedOrExtendedVersionThreeBackupCreatesNothing()
			throws Exception {
		byte[] file = v3("Alice", database(2 * BackupStream.MIN_CHUNK), PASSPHRASE);
		int body = BackupStream.HEADER_LENGTH + BackupStream.MAC_LENGTH;
		for (int cut : new int[] {body + 1, body + BackupStream.MIN_CHUNK,
				file.length - BackupStream.TAG_LENGTH, file.length - 1}) {
			assertRefused(Arrays.copyOf(file, cut), CORRUPT);
		}
		assertRefused(Arrays.copyOf(file, file.length + 7), CORRUPT);
		byte[] flipped = file.clone();
		flipped[flipped.length - 2] ^= 1;
		assertRefused(flipped, CORRUPT);
		verify(accounts, never()).importProfile(anyString(), any(char[].class),
				any(File.class), any(byte[].class));
	}

	@Test
	public void theWrongPassphraseIsRefusedBeforeAnyChunkIsRead()
			throws Exception {
		byte[] file = v3("Alice", database(100), "other".toCharArray());
		assertRefused(file, WRONG_PASSPHRASE);
		assertRefused(Arrays.copyOf(file,
				BackupStream.HEADER_LENGTH + BackupStream.MAC_LENGTH),
				WRONG_PASSPHRASE);
	}

	@Test
	public void filesThatAreNotBackupsOrFromANewerVersionAreRefused()
			throws Exception {
		assertRefused("not a backup".getBytes(StandardCharsets.UTF_8),
				NOT_A_BACKUP);
		assertRefused(new byte[0], NOT_A_BACKUP);
		assertRefused(new byte[] {'Z', 'B', 'K', '1'}, CORRUPT);
		assertRefused(new byte[] {'Z', 'B', 'K', '1', 4, 0}, UNSUPPORTED_VERSION);
		assertRefused(new byte[] {'Z', 'B', 'K', '1', 3, 0}, CORRUPT);
	}

	@Test
	public void aVersionTwoBackupOfTheEarlierCodeStillImports()
			throws Exception {
		byte[] file = hex(BackupFormatCompatibilityTest.EARLIER_SEALED);
		manager().importAccount(new ByteArrayInputStream(file), file.length,
				"fixture passphrase".toCharArray(), NEW_PASSWORD.clone());
		assertEquals("Old Backup é", names.get(0));
		assertArrayEquals(BackupBundle.fromBytes(hex(EARLIER_BUNDLE)).dbFile,
				restored.get(0));
	}

	private static byte[] v1(String name, byte[] db, char[] pass) {
		byte[] key = new byte[32];
		byte[] bundle = new BackupBundle(name, key, db, null).toBytes();
		Argon2 argon2 = new Argon2();
		VaultCrypto crypto = new VaultCrypto();
		byte[] salt = argon2.generateSalt();
		ByteBuffer header = ByteBuffer.allocate(54);
		header.put(new byte[] {'Z', 'B', 'K', '1', 1, 0});
		header.put(salt);
		header.put(FAST.toBytes());
		byte[] master = argon2.deriveKey(pass, salt, FAST);
		byte[] base = "Zerion-Account-Backup-ZBK1".getBytes(StandardCharsets.UTF_8);
		byte[] aad = Arrays.copyOf(base, base.length + 2);
		aad[base.length] = 1;
		byte[] sealed = crypto.encrypt(bundle, master, aad).toBytes();
		ByteBuffer file = ByteBuffer.allocate(54 + 32 + 4 + sealed.length);
		file.put(header.array());
		file.put(crypto.computePasswordVerificationMac(master));
		file.putInt(sealed.length);
		file.put(sealed);
		return file.array();
	}

	@Test
	public void aVersionOneBackupStillImports() throws Exception {
		byte[] db = database(500);
		char[] pass = "passphrase".toCharArray();
		byte[] file = v1("Carol", db, pass);
		manager().importAccount(new ByteArrayInputStream(file), file.length,
				pass, NEW_PASSWORD.clone());
		assertEquals("Carol", names.get(0));
		assertArrayEquals(db, restored.get(0));
	}

	private void assertTrailingBytesRefused(byte[] file, char[] pass)
			throws IOException {
		for (byte[] extra : new byte[][] {{0}, {1, 2, 3}, file}) {
			byte[] longer = Arrays.copyOf(file, file.length + extra.length);
			System.arraycopy(extra, 0, longer, file.length, extra.length);
			try {
				manager().importAccount(new ByteArrayInputStream(longer),
						longer.length, pass.clone(), NEW_PASSWORD.clone());
				fail("bytes after the message were accepted");
			} catch (BackupException e) {
				assertEquals(CORRUPT, e.reason);
			}
		}
		assertTrue("a profile was created from a refused file",
				restored.isEmpty());
	}

	@Test
	public void bytesAfterTheMessageOfAnEarlierVersionAreRefused()
			throws Exception {
		assertTrailingBytesRefused(
				hex(BackupFormatCompatibilityTest.EARLIER_SEALED),
				"fixture passphrase".toCharArray());
		char[] pass = "passphrase".toCharArray();
		assertTrailingBytesRefused(v1("Carol", database(500), pass), pass);
	}

	@Test
	public void anEarlierVersionTooLargeForTheHeapIsRefusedBeforeReading()
			throws Exception {
		InputStream unread = new InputStream() {
			private final byte[] head = {'Z', 'B', 'K', '1', 2};
			private int served;

			@Override
			public int read() {
				byte[] one = new byte[1];
				return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
			}

			@Override
			public int read(byte[] b, int off, int len) {
				if (served == head.length) {
					throw new AssertionError("the file was read");
				}
				int n = Math.min(len, head.length - served);
				System.arraycopy(head, served, b, off, n);
				served += n;
				return n;
			}
		};
		try {
			manager().importAccount(unread, BackupMemory.limit() + 1,
					PASSPHRASE.clone(), NEW_PASSWORD.clone());
			fail("expected TOO_LARGE");
		} catch (BackupException e) {
			assertEquals(TOO_LARGE, e.reason);
		}
	}
}
