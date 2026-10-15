package com.professor.zerion.android.backup;

import android.app.Application;

import com.professor.zerion.android.util.CacheSweeper;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.account.AndroidAccountManager;
import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.transport.RootKeyStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.backup.BackupException.Reason.IO_ERROR;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BackupCopyPauseTest {

	private static final char[] PASSPHRASE = "a backup passphrase".toCharArray();

	private final Application app = ApplicationProvider.getApplicationContext();
	private final List<Long> pauses = new ArrayList<>();
	private byte[] snapshot = new byte[64];
	private RootKeyStore store;

	@After
	public void noCopyIsLeftBehind() {
		File[] left = app.getCacheDir().listFiles(
				(dir, n) -> n.startsWith("zbk-"));
		assertTrue(left == null || left.length == 0);
	}

	private static final AccountBackupManager.Sealer PLAIN =
			(bundle, length, passphrase, out) -> {
				byte[] buf = new byte[8192];
				int n;
				while ((n = bundle.read(buf)) != -1) out.write(buf, 0, n);
			};

	private static final AccountBackupManager.Sealer FAILING =
			(bundle, length, passphrase, out) -> {
				throw new RuntimeException("Encryption failed");
			};

	private AccountBackupManager manager(String name,
			AccountBackupManager.Sealer sealer) {
		DatabaseComponent db = db();
		store = new RootKeyStore(db);
		AndroidAccountManager accounts = mock(AndroidAccountManager.class);
		when(accounts.getDatabaseKey()).thenReturn(
				new SecretKey(new byte[SecretKey.LENGTH]));
		ProfileManager profiles = mock(ProfileManager.class);
		when(profiles.getActiveProfileId()).thenReturn("p");
		when(profiles.readDisplayName("p")).thenReturn(name);
		return new AccountBackupManager(app, db, accounts, profiles,
				mock(IdentityManager.class), store, new BackupCrypto(), sealer);
	}

	private DatabaseComponent db() {
		return (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "transaction":
						case "transactionWithResult":
						case "transactionWithNullableResult":
							return run(args[1]);
						case "mergeSettings":
							Settings s = (Settings) args[1];
							if (s.containsKey(RootKeyStore.KEY_PAUSED_UNTIL)) {
								pauses.add(s.getLong(
										RootKeyStore.KEY_PAUSED_UNTIL, 0));
							}
							return null;
						case "getSettings":
							return new Settings();
						default:
							return null;
					}
				});
	}

	private Object run(Object body) throws Throwable {
		Transaction txn = new Transaction(connection(), false);
		for (Method m : body.getClass().getMethods()) {
			if (m.getName().equals("run") || m.getName().equals("call")) {
				m.setAccessible(true);
				try {
					return m.invoke(body, txn);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
			}
		}
		throw new AssertionError();
	}

	private Connection connection() {
		Statement statement = (Statement) Proxy.newProxyInstance(
				Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
				(proxy, method, args) -> {
					if (method.getName().equals("execute")) {
						String sql = (String) args[0];
						int q = sql.indexOf('\'');
						String target = sql.substring(q + 1, sql.length() - 1)
								.replace("''", "'");
						try (FileOutputStream out = new FileOutputStream(target)) {
							out.write(snapshot);
						}
						return true;
					}
					if (method.getReturnType() == boolean.class) return false;
					return null;
				});
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] {Connection.class},
				(proxy, method, args) -> {
					if (method.getName().equals("createStatement")) {
						return statement;
					}
					if (method.getReturnType() == boolean.class) return false;
					return null;
				});
	}

	private void assertNoPauseAndTheGateIsOpen() {
		assertEquals("an incomplete copy wrote a pause", 0, pauses.size());
		assertFalse(store.isSnapshotting());
	}

	@Test
	public void aSavedExportPausesRootEvolutionOnce() throws Exception {
		snapshot = new byte[4096];
		new Random(1).nextBytes(snapshot);
		AccountBackupManager manager = manager("Name", PLAIN);
		ByteArrayOutputStream saved = new ByteArrayOutputStream();
		long before = System.currentTimeMillis();
		manager.exportAccount(PASSPHRASE.clone(), () -> saved);
		long after = System.currentTimeMillis();

		assertEquals(1, pauses.size());
		long until = pauses.get(0);
		assertTrue(until >= before + AccountBackupManager.ROOT_EVOLUTION_PAUSE_MS);
		assertTrue(until <= after + AccountBackupManager.ROOT_EVOLUTION_PAUSE_MS);
		assertFalse(store.isSnapshotting());
		BackupBundle bundle = BackupBundle.fromBytes(saved.toByteArray());
		assertEquals("Name", bundle.displayName);
		assertArrayEquals(snapshot, bundle.dbFile);
	}

	@Test
	public void theAppLockingDuringACopyLeavesItsSnapshotIntact()
			throws Exception {
		snapshot = new byte[256 * 1024];
		new Random(5).nextBytes(snapshot);
		AccountBackupManager manager = manager("Name", PLAIN);
		ByteArrayOutputStream copied = new ByteArrayOutputStream();
		manager.copyAccount((bundle, length) -> {
			byte[] buf = new byte[4096];
			boolean swept = false;
			int n;
			while ((n = bundle.read(buf)) != -1) {
				copied.write(buf, 0, n);
				if (!swept && copied.size() > length / 2) {
					swept = true;
					CacheSweeper.sweep(app);
				}
			}
		});
		assertEquals(1, pauses.size());
		BackupBundle bundle = BackupBundle.fromBytes(copied.toByteArray());
		assertArrayEquals(snapshot, bundle.dbFile);
	}

	@Test
	public void aPanicDuringAnExportStopsItAndDiscardsTheDocument()
			throws Exception {
		snapshot = new byte[256 * 1024];
		new Random(7).nextBytes(snapshot);
		AccountBackupManager manager = manager("Name", PLAIN);
		AtomicInteger discarded = new AtomicInteger();
		boolean[] closed = {false};
		ByteArrayOutputStream written = new ByteArrayOutputStream();
		OutputStream document = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				write(new byte[] {(byte) b}, 0, 1);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				if (closed[0]) throw new IOException("closed");
				written.write(b, off, len);
				if (written.size() > 64 * 1024) RunningCopies.abortAll();
			}

			@Override
			public void close() {
				closed[0] = true;
			}
		};
		try {
			manager.exportAccount(PASSPHRASE.clone(),
					new AccountBackupManager.Destination() {
						@Override
						public OutputStream open() {
							return document;
						}

						@Override
						public void discard() {
							discarded.incrementAndGet();
						}
					});
			fail("the export went on after the panic");
		} catch (BackupException e) {
			assertEquals(IO_ERROR, e.reason);
		}
		assertTrue("the document was left open", closed[0]);
		assertEquals("the document was not discarded", 1, discarded.get());
		assertTrue(written.size() < snapshot.length);
		assertNoPauseAndTheGateIsOpen();
		assertEquals(0, RunningCopies.running());
	}

	@Test
	public void aPanicDuringATransferStopsTheCopy() {
		snapshot = new byte[256 * 1024];
		new Random(8).nextBytes(snapshot);
		AccountBackupManager manager = manager("Name", PLAIN);
		long[] read = new long[1];
		try {
			manager.copyAccount((bundle, length) -> {
				byte[] buf = new byte[4096];
				int n;
				while ((n = bundle.read(buf)) != -1) {
					read[0] += n;
					if (read[0] > length / 2) RunningCopies.abortAll();
				}
			});
			fail("the copy went on after the panic");
		} catch (BackupException e) {
			assertEquals(IO_ERROR, e.reason);
		}
		assertTrue(read[0] < snapshot.length);
		assertNoPauseAndTheGateIsOpen();
		assertEquals(0, RunningCopies.running());
	}

	@Test
	public void aPanicAfterACopyEndedLeavesItAlone() throws Exception {
		AccountBackupManager manager = manager("Name", PLAIN);
		ByteArrayOutputStream saved = new ByteArrayOutputStream();
		AtomicInteger discarded = new AtomicInteger();
		manager.exportAccount(PASSPHRASE.clone(),
				new AccountBackupManager.Destination() {
					@Override
					public OutputStream open() {
						return saved;
					}

					@Override
					public void discard() {
						discarded.incrementAndGet();
					}
				});
		RunningCopies.abortAll();
		assertEquals(0, discarded.get());
		assertEquals(1, pauses.size());
	}

	@Test
	public void aCopyThatCannotBeSerialisedLeavesNoPause() {
		char[] longName = new char[70_000];
		Arrays.fill(longName, 'a');
		AccountBackupManager manager =
				manager(new String(longName), PLAIN);
		try {
			manager.exportAccount(PASSPHRASE.clone(), ByteArrayOutputStream::new);
			fail("expected the export to fail");
		} catch (BackupException e) {
			assertEquals(IO_ERROR, e.reason);
		}
		assertNoPauseAndTheGateIsOpen();
	}

	@Test
	public void aFailedEncryptionLeavesNoPause() throws BackupException {
		AccountBackupManager manager = manager("Name", FAILING);
		try {
			manager.exportAccount(PASSPHRASE.clone(), ByteArrayOutputStream::new);
			fail("expected the export to fail");
		} catch (RuntimeException expected) {
		}
		assertNoPauseAndTheGateIsOpen();
	}

	@Test
	public void aDestinationThatCannotBeOpenedLeavesNoPause() {
		AccountBackupManager manager = manager("Name", PLAIN);
		try {
			manager.exportAccount(PASSPHRASE.clone(), () -> {
				throw new IOException("no space left");
			});
			fail("expected the export to fail");
		} catch (BackupException e) {
			assertEquals(IO_ERROR, e.reason);
		}
		assertNoPauseAndTheGateIsOpen();
	}

	@Test
	public void aSaveCancelledHalfwayLeavesNoPause() {
		AccountBackupManager manager = manager("Name", PLAIN);
		OutputStream cancelled = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new InterruptedIOException("cancelled");
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				throw new InterruptedIOException("cancelled");
			}
		};
		try {
			manager.exportAccount(PASSPHRASE.clone(), () -> cancelled);
			fail("expected the export to fail");
		} catch (BackupException e) {
			assertEquals(IO_ERROR, e.reason);
		}
		assertNoPauseAndTheGateIsOpen();
	}

	@Test
	public void aTransferThatBreaksLeavesNoPause() {
		AccountBackupManager manager = manager("Name", PLAIN);
		try {
			manager.copyAccount((bundle, length) -> {
				throw new IOException("connection reset");
			});
			fail("expected the copy to fail");
		} catch (BackupException e) {
			assertEquals(IO_ERROR, e.reason);
		}
		assertNoPauseAndTheGateIsOpen();
	}

	@Test
	public void failedAttemptsNeverExtendAnEarlierPause() throws Exception {
		AccountBackupManager manager = manager("Name", PLAIN);
		manager.exportAccount(PASSPHRASE.clone(), ByteArrayOutputStream::new);
		assertEquals(1, pauses.size());
		for (int i = 0; i < 3; i++) {
			try {
				manager.exportAccount(PASSPHRASE.clone(), () -> {
					throw new IOException("no space left");
				});
				fail("expected the export to fail");
			} catch (BackupException e) {
				assertEquals(IO_ERROR, e.reason);
			}
		}
		assertEquals("failed attempts wrote no later pause", 1, pauses.size());
		assertFalse(store.isSnapshotting());
	}

	@Test
	public void theSnapshotIsStreamedWithoutBeingHeldInMemory()
			throws Exception {
		ThreadAllocations allocations = ThreadAllocations.require();
		int size = 8 * 1024 * 1024;
		snapshot = new byte[size];
		new Random(3).nextBytes(snapshot);
		AccountBackupManager manager = manager("Name", PLAIN);
		byte[] buf = new byte[8192];
		manager.copyAccount((bundle, length) -> {
			while (bundle.read(buf) != -1) {
			}
		});
		pauses.clear();
		long[] used = new long[1];
		long[] streamed = new long[1];
		long before = allocations.now();
		manager.copyAccount((bundle, length) -> {
			int n;
			while ((n = bundle.read(buf)) != -1) streamed[0] += n;
			used[0] = allocations.now() - before;
			assertEquals(length, streamed[0]);
		});
		assertEquals(1, pauses.size());
		assertTrue(streamed[0] > size);
		assertTrue("copying " + size + " bytes allocated " + used[0],
				used[0] < 1024 * 1024);
	}
}
