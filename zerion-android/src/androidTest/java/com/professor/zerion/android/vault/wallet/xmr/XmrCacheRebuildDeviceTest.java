package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.android.vault.ui.Event;
import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-device proof of the persistent-cache failure model against the real
 * native library, without touching the app's vault: an in-memory store and
 * vault gate stand in for ZVault (the device has real org.json, so the
 * settings path is exercised), and the cache lives under a temporary
 * directory that is removed afterwards. Shows that a corrupted cache is
 * rejected and rebuilt from the seed, that a cache belonging to another wallet
 * is rejected, and that two wallets never share cache directories.
 */
@RunWith(AndroidJUnit4.class)
public class XmrCacheRebuildDeviceTest {

	private Context ctx;
	private File base;
	private FakeGate gate;
	private FakeStore store;
	private CountingEngine engine;
	private XmrWalletManager mgr;

	@Before
	public void setUp() throws Exception {
		ctx = ApplicationProvider.getApplicationContext();
		assertTrue(NativeMonero.isAvailable());
		base = new File(ctx.getCacheDir(), "xmr-rebuild-" + System.nanoTime());
		assertTrue(base.mkdirs());
		gate = new FakeGate();
		store = new FakeStore();
		engine = new CountingEngine(new NativeMoneroEngine());
		mgr = new XmrWalletManager(base, gate, store, engine,
				Executors.newSingleThreadExecutor(),
				Executors.newSingleThreadExecutor());
		mgr.setTorSocksPort(-1);
	}

	@After
	public void tearDown() {
		mgr.closeSession();
		sleep(500);
		deleteTree(base);
	}

	@Test
	public void corruptBackgroundFileIsRebuiltFromSeed() throws Exception {
		String id = create("A");
		open(id);
		String primary = engine.lastAddress();
		assertNotNull(primary);
		File dir = liveDir(id);
		mgr.closeSession();
		waitFor(() -> new File(dir, "w.background").isFile()
				&& new File(dir, "w.background.keys").isFile(), 10_000);
		sleep(300);

		engine.reset();
		overwriteRandom(new File(dir, "w.background.keys"));
		open(id);
		assertTrue("a corrupt view-only cache is rebuilt from the seed",
				engine.restores.get() >= 1);
		assertEquals("the rebuilt wallet keeps the same identity", primary,
				engine.lastAddress());
		assertTrue(mgr.isSessionValid());
		assertEquals(id, mgr.openWalletId());
		assertFalse("no plaintext address file after rebuild",
				new File(dir, "w.address.txt").exists());
	}

	@Test
	public void foreignBackgroundFileIsRejectedAndWalletsStayIsolated()
			throws Exception {
		String a = create("A");
		String b = create("B");
		open(a);
		String primaryA = engine.lastAddress();
		mgr.closeSession();
		File dirA = liveDir(a);
		waitFor(() -> new File(dirA, "w.background").isFile(), 10_000);
		open(b);
		String primaryB = engine.lastAddress();
		mgr.closeSession();
		File dirB = liveDir(b);
		waitFor(() -> new File(dirB, "w.background").isFile(), 10_000);
		assertFalse("distinct wallets have distinct identities",
				primaryA.equals(primaryB));
		sleep(300);

		copy(new File(dirB, "w.background"), new File(dirA, "w.background"));
		copy(new File(dirB, "w.background.keys"),
				new File(dirA, "w.background.keys"));
		engine.reset();
		open(a);
		assertTrue("A rebuilds from A's own seed, never adopting B",
				engine.restores.get() >= 1);
		assertEquals("A resolves to A's identity", primaryA,
				engine.lastAddress());
		assertEquals(a, mgr.openWalletId());
		mgr.closeSession();
		waitFor(() -> mgr.openWalletId() == null, 5_000);
		sleep(300);

		engine.reset();
		open(b);
		assertEquals("B still resolves to B's identity", primaryB,
				engine.lastAddress());
		assertEquals(b, mgr.openWalletId());
	}

	@Test
	public void createdWalletOpensForViewWithoutHanging() throws Exception {
		String id = create("V");
		assertFalse("a freshly created V2 wallet needs no password to open",
				mgr.needsPasswordToOpen(id));
		CountDownLatch latch = new CountDownLatch(1);
		androidx.lifecycle.Observer<Event<String>> obs = ev -> {
			String v = ev == null ? null : ev.getIfNotHandled();
			if (id.equals(v)) latch.countDown();
		};
		androidx.lifecycle.Observer<Event<XmrError>> err = ev -> {
			XmrError e = ev == null ? null : ev.getIfNotHandled();
			if (e != null) latch.countDown();
		};
		runOnMain(() -> {
			mgr.getSessionOpened().observeForever(obs);
			mgr.getError().observeForever(err);
		});
		mgr.openWalletForView(id);
		assertTrue("open-for-view must complete, never hang the executor",
				latch.await(60, TimeUnit.SECONDS));
		runOnMain(() -> {
			mgr.getSessionOpened().removeObserver(obs);
			mgr.getError().removeObserver(err);
		});
		assertTrue("the view session is valid", mgr.isSessionValid());
		assertEquals(id, mgr.openWalletId());
	}

	private String create(String name) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		String[] out = new String[1];
		androidx.lifecycle.Observer<Event<String>> obs = ev -> {
			String v = ev == null ? null : ev.getIfNotHandled();
			if (v != null) {
				out[0] = v;
				latch.countDown();
			}
		};
		runOnMain(() -> mgr.getSeedReveal().observeForever(obs));
		mgr.createWallet(name, "pw".toCharArray());
		assertTrue("create completes", latch.await(60, TimeUnit.SECONDS));
		runOnMain(() -> mgr.getSeedReveal().removeObserver(obs));
		char[] seed = mgr.takePendingSeed(out[0]);
		assertNotNull(seed);
		java.util.Arrays.fill(seed, '\0');
		return out[0];
	}

	private void open(String id) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		androidx.lifecycle.Observer<Event<String>> obs = ev -> {
			String v = ev == null ? null : ev.getIfNotHandled();
			if (id.equals(v)) latch.countDown();
		};
		androidx.lifecycle.Observer<Event<XmrError>> err = ev -> {
			XmrError e = ev == null ? null : ev.getIfNotHandled();
			if (e != null) latch.countDown();
		};
		runOnMain(() -> {
			mgr.getSessionOpened().observeForever(obs);
			mgr.getError().observeForever(err);
		});
		mgr.openWallet(id, "pw".toCharArray());
		assertTrue("open completes", latch.await(120, TimeUnit.SECONDS));
		runOnMain(() -> {
			mgr.getSessionOpened().removeObserver(obs);
			mgr.getError().removeObserver(err);
		});
		assertEquals("session belongs to the requested wallet", id,
				mgr.openWalletId());
	}

	private File liveDir(String id) {
		return new File(new File(base, "live"), XmrWalletManager.dirNameFor(id));
	}

	private static void overwriteRandom(File f) throws Exception {
		byte[] junk = new byte[(int) Math.max(64, Math.min(f.length(), 4096))];
		new SecureRandom().nextBytes(junk);
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
			raf.seek(0);
			raf.write(junk);
		}
	}

	private static void copy(File from, File to) throws Exception {
		java.nio.file.Files.copy(from.toPath(), to.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	private interface Cond {
		boolean ok();
	}

	private static void waitFor(Cond c, long ms) {
		long end = System.currentTimeMillis() + ms;
		while (!c.ok() && System.currentTimeMillis() < end) sleep(50);
		assertTrue("condition within " + ms + "ms", c.ok());
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
		}
	}

	private static void runOnMain(Runnable r) throws Exception {
		CountDownLatch l = new CountDownLatch(1);
		new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
			r.run();
			l.countDown();
		});
		assertTrue(l.await(10, TimeUnit.SECONDS));
	}

	private static void deleteTree(File f) {
		File[] kids = f.listFiles();
		if (kids != null) for (File k : kids) deleteTree(k);
		f.delete();
	}

	private static final class FakeGate implements VaultGate {
		@Override
		public boolean isUnlocked() {
			return true;
		}

		@Override
		public long getLockGeneration() {
			return 7;
		}

		@Override
		public void addLockListener(Runnable listener) {
		}
	}

	private static final class FakeStore implements XmrStore {
		private final Map<String, char[]> seeds = new HashMap<>();
		private final List<WalletRecord> records = new ArrayList<>();
		private final Object monitor = new Object();
		@Nullable
		private String settings;
		private int n;

		@Override
		public synchronized String createWallet(WalletCoin coin, String name,
				char[] mnemonic, @Nullable char[] password) {
			String id = "w" + (++n);
			seeds.put(id, mnemonic.clone());
			records.add(new WalletRecord(id, coin, name, 0, true));
			return id;
		}

		@Override
		public synchronized char[] loadMnemonicChars(String walletId,
				@Nullable char[] password) {
			char[] s = seeds.get(walletId);
			if (s == null) throw new SecurityException("unknown");
			return s.clone();
		}

		@Override
		public synchronized List<WalletRecord> listWallets() {
			return new ArrayList<>(records);
		}

		@Override
		public synchronized void deleteWallet(String walletId) {
			seeds.remove(walletId);
			records.removeIf(r -> r.id.equals(walletId));
		}

		@Nullable
		@Override
		public String readSettings() {
			return settings;
		}

		@Override
		public void writeSettings(String json) {
			settings = json;
		}

		@Override
		public Object settingsMonitor() {
			return monitor;
		}

		final java.util.Map<String, String> journals =
				new java.util.HashMap<>();

		@Nullable
		@Override
		public String readSpendJournal(String walletId) {
			return journals.get(walletId);
		}

		@Override
		public void writeSpendJournal(String walletId, String journal) {
			journals.put(walletId, journal);
		}

		@Override
		public void removeSpendJournal(String walletId) {
			journals.remove(walletId);
		}
	}

	/**
	 * Delegates to the real engine and records what each restore/open produced:
	 * whether an open yielded a usable session, and the primary address of the
	 * last usable session (identity evidence). Addresses are compared only in
	 * memory and never logged.
	 */
	private static final class CountingEngine implements MoneroEngine {
		final AtomicInteger restores = new AtomicInteger();
		final AtomicInteger openedOk = new AtomicInteger();
		private final MoneroEngine real;
		@Nullable
		private volatile String last;

		CountingEngine(MoneroEngine real) {
			this.real = real;
		}

		void reset() {
			restores.set(0);
			openedOk.set(0);
		}

		@Nullable
		String lastAddress() {
			return last;
		}

		@Override
		public boolean isAvailable() {
			return real.isAvailable();
		}

		@Nullable
		@Override
		public Session create(String path, char[] password, String language) {
			return real.create(path, password, language);
		}

		@Nullable
		@Override
		public Session restore(String path, char[] password, char[] seed,
				long restoreHeight, char[] seedOffset) {
			restores.incrementAndGet();
			Session s = real.restore(path, password, seed, restoreHeight,
					seedOffset);
			if (s != null && s.status() == 0) last = s.address(0, 0);
			return s;
		}

		@Nullable
		@Override
		public Session open(String path, char[] password) {
			Session s = real.open(path, password);
			if (s != null && s.status() == 0) {
				openedOk.incrementAndGet();
				last = s.address(0, 0);
			}
			return s;
		}

		@Override
		public boolean validateAddress(String address) {
			return real.validateAddress(address);
		}

		@Override
		public AddressKind addressKind(String address) {
			return real.addressKind(address);
		}
	}
}
