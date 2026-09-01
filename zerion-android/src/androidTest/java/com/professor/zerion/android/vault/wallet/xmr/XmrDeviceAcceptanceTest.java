package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.ui.Event;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * XMR-P1 on-device acceptance over the REAL production stack: a real
 * {@link VaultManager} (ZVault master key, Argon2id + AES-GCM), a real
 * {@link WalletStore} sealing the seed, and the real {@link NativeMoneroEngine}
 * (libzmonero.so). No fakes in the security path. Runs headless on an isolated,
 * non-funded emulator via {@code am instrument}. Exercises create, restart
 * persistence, second-authentication (correct/wrong), import determinism,
 * delete, forensic residue, and ephemeral working-directory shred, and writes a
 * timing/summary file that the harness pulls back.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class XmrDeviceAcceptanceTest {

	private static final char[] VAULT_PW = "vault-pass-9271".toCharArray();
	private static final char[] W_PW = "wallet-pass-3184".toCharArray();

	private Context ctx;
	private VaultManager vault;
	private WalletStore store;

	private static final StringBuilder report = new StringBuilder();

	@Before
	public void setUp() throws Exception {
		ctx = ApplicationProvider.getApplicationContext();
		assertTrue("libzmonero.so must load on this ABI",
				NativeMonero.isAvailable());
		vault = new VaultManager(ctx);
		if (!safeUnlock()) {
			vault.createVault(VAULT_PW.clone());
			assertTrue("vault unlock after create", safeUnlock());
		}
		store = new WalletStore(vault);
	}

	private boolean safeUnlock() {
		try {
			return vault.unlockVault(VAULT_PW.clone());
		} catch (Exception e) {
			return false;
		}
	}

	private XmrWalletManager newManager() {
		return new XmrWalletManager(ctx, vault, store, new NativeMoneroEngine());
	}

	@Test
	public void a_createProducesSeedAndListsWallet() throws Exception {
		XmrWalletManager m = newManager();
		long t0 = System.nanoTime();
		char[] seed = awaitSeed(m, m.getError(),
				() -> m.createWallet("Acc-A", W_PW.clone()));
		long createMs = (System.nanoTime() - t0) / 1_000_000;
		assertNotNull("create must reveal a seed", seed);
		assertEquals("Monero seed is 25 words", 25,
				new String(seed).trim().split("\\s+").length);
		report.append("create_tap_to_seed_ms=").append(createMs).append('\n');

		List<WalletRecord> ws = store.listWallets();
		int xmr = 0;
		String id = null;
		for (WalletRecord w : ws) {
			if (w.coin == com.professor.zerion.android.vault.wallet.WalletCoin.XMR) {
				xmr++;
				id = w.id;
			}
		}
		assertEquals("exactly one XMR wallet stored", 1, xmr);
		assertNotNull(id);

		File sealed = residueScanRoot();
		assertFalse("plaintext seed must not be present in app-data after create",
				fileTreeContains(sealed, new String(seed).trim()
						.getBytes(StandardCharsets.UTF_8)));
		report.append("forensic_after_create=clean\n");
		m.closeSession();
		java.util.Arrays.fill(seed, '\0');
	}

	@Test
	public void b_restartPersistenceAndSecondAuth() throws Exception {
		XmrWalletManager creator = newManager();
		char[] seed = awaitSeed(creator, creator.getError(),
				() -> creator.createWallet("Acc-B", W_PW.clone()));
		assertNotNull(seed);
		String created = new String(seed).trim();
		creator.closeSession();
		String id = firstXmrIdNamed("Acc-B");

		XmrWalletManager reopened = newManager();
		long t0 = System.nanoTime();
		String opened = awaitString(reopened.getSessionOpened(),
				reopened.getError(), () -> reopened.openWallet(id, W_PW.clone()));
		long openMs = (System.nanoTime() - t0) / 1_000_000;
		assertEquals("reopen after restart returns same wallet id", id, opened);
		assertTrue("session valid after reopen", reopened.isSessionValid());
		report.append("reopen_submit_to_session_ms=").append(openMs).append('\n');

		char[] revealed = awaitSeed(reopened, reopened.getError(),
				() -> reopened.revealSeed(id, W_PW.clone()));
		assertNotNull(revealed);
		assertEquals("revealed seed matches created seed", created,
				new String(revealed).trim());
		java.util.Arrays.fill(revealed, '\0');
		reopened.closeSession();
		java.util.Arrays.fill(seed, '\0');
	}

	@Test
	public void c_wrongPasswordFailsClosed() throws Exception {
		XmrWalletManager creator = newManager();
		char[] seed = awaitSeed(creator, creator.getError(),
				() -> creator.createWallet("Acc-C", W_PW.clone()));
		assertNotNull(seed);
		creator.closeSession();
		String id = firstXmrIdNamed("Acc-C");

		XmrWalletManager m = newManager();
		XmrError err = awaitError(m.getSessionOpened(), m.getError(),
				() -> m.openWallet(id, "totally-wrong".toCharArray()));
		assertEquals("wrong password is rejected", XmrError.WRONG_PASSWORD, err);
		assertFalse("no session after wrong password", m.isSessionValid());
		assertNull(m.openWalletId());
		java.util.Arrays.fill(seed, '\0');
	}

	@Test
	public void d_importDeterminismMatchesCreate() throws Exception {
		XmrWalletManager creator = newManager();
		char[] seed = awaitSeed(creator, creator.getError(),
				() -> creator.createWallet("Acc-D1", W_PW.clone()));
		assertNotNull(seed);
		creator.closeSession();
		String created = new String(seed).trim();

		String addr1 = addressFromSeed(seed.clone());
		String addr2 = addressFromSeed(seed.clone());
		assertTrue("primary address is mainnet", addr1.startsWith("4"));
		assertEquals("same seed derives the same primary address", addr1, addr2);

		XmrWalletManager importer = newManager();
		char[] importSeed = seed.clone();
		InstrumentationRegistry.getInstrumentation().runOnMainSync(
				() -> importer.importWallet("Acc-D2", importSeed, 0,
						W_PW.clone()));
		String importedId = pollForWalletId("Acc-D2", 60000);
		assertNotNull("imported wallet stored", importedId);
		final String openId = importedId;

		char[] revealed = awaitSeed(importer,
				importer.getError(),
				() -> importer.revealSeed(openId, W_PW.clone()));
		assertNotNull(revealed);
		assertEquals("imported seed round-trips through ZVault unchanged",
				created, new String(revealed).trim());
		assertEquals("imported seed derives the same address",
				addr1, addressFromSeed(revealed.clone()));
		java.util.Arrays.fill(revealed, '\0');
		java.util.Arrays.fill(seed, '\0');
	}

	@Test
	public void e_deleteRequiresAuthAndRemoves() throws Exception {
		XmrWalletManager creator = newManager();
		char[] seed = awaitSeed(creator, creator.getError(),
				() -> creator.createWallet("Acc-E", W_PW.clone()));
		assertNotNull(seed);
		creator.closeSession();
		String id = firstXmrIdNamed("Acc-E");

		XmrWalletManager m = newManager();
		XmrError wrong = awaitError(m.getWalletDeleted(), m.getError(),
				() -> m.deleteWallet(id, "nope".toCharArray()));
		assertEquals("delete with wrong password rejected",
				XmrError.WRONG_PASSWORD, wrong);
		assertNotNull("wallet still present after failed delete",
				firstXmrIdNamed("Acc-E"));

		String deleted = awaitString(m.getWalletDeleted(), m.getError(),
				() -> m.deleteWallet(id, W_PW.clone()));
		assertEquals(id, deleted);
		assertNull("wallet gone after authorized delete",
				firstXmrIdNamed("Acc-E"));
		java.util.Arrays.fill(seed, '\0');
	}

	@Test
	public void f_ephemeralWorkingDirShredded() throws Exception {
		File xmrBase = new File(ctx.getNoBackupFilesDir(), "xmr");
		XmrWalletManager creator = newManager();
		char[] seed = awaitSeed(creator, creator.getError(),
				() -> creator.createWallet("Acc-F", W_PW.clone()));
		assertNotNull(seed);
		creator.closeSession();
		Thread.sleep(400);
		File[] leftovers = xmrBase.listFiles();
		int remaining = leftovers == null ? 0 : leftovers.length;
		report.append("working_dirs_after_create_close=").append(remaining)
				.append('\n');
		assertEquals("ephemeral working dirs shredded after close", 0, remaining);
		assertFalse("no plaintext seed anywhere under app-data",
				fileTreeContains(residueScanRoot(), new String(seed).trim()
						.getBytes(StandardCharsets.UTF_8)));
		report.append("forensic_after_ephemeral=clean\n");
		writeReport();
		java.util.Arrays.fill(seed, '\0');
	}

	@Test
	public void g_renameReconcileHealsCrashedRename() throws Exception {
		XmrWalletManager creator = newManager();
		char[] seed = awaitSeed(creator, creator.getError(),
				() -> creator.createWallet("Acc-G", W_PW.clone()));
		assertNotNull(seed);
		creator.closeSession();
		String created = new String(seed).trim();
		String oldId = firstXmrIdNamed("Acc-G");
		assertNotNull(oldId);

		String newId = store.createWallet(
				com.professor.zerion.android.vault.wallet.WalletCoin.XMR,
				"Acc-G2", seed.clone(), W_PW.clone());
		writeRenameJournalRaw(oldId, "Acc-G2", newId);
		assertNotNull(firstXmrIdNamed("Acc-G"));
		assertNotNull(firstXmrIdNamed("Acc-G2"));

		XmrWalletManager m = newManager();
		m.loadWallets();
		assertTrue("reconcile removes the leftover old item",
				pollUntilGone("Acc-G", 30000));
		assertNotNull("renamed item survives", firstXmrIdNamed("Acc-G2"));

		final String survivorId = firstXmrIdNamed("Acc-G2");
		char[] revealed = awaitSeed(m, m.getError(),
				() -> m.revealSeed(survivorId, W_PW.clone()));
		assertNotNull(revealed);
		assertEquals("survivor holds the same seed", created,
				new String(revealed).trim());
		java.util.Arrays.fill(revealed, '\0');
		java.util.Arrays.fill(seed, '\0');
	}

	@Test
	public void h_reconcileNeverDeletesLastCopy() throws Exception {
		XmrWalletManager creator = newManager();
		char[] seed = awaitSeed(creator, creator.getError(),
				() -> creator.createWallet("Acc-H", W_PW.clone()));
		assertNotNull(seed);
		creator.closeSession();
		String oldId = firstXmrIdNamed("Acc-H");
		assertNotNull(oldId);

		writeRenameJournalRaw(oldId, "Acc-H2", "nonexistent-target-id");
		XmrWalletManager m = newManager();
		m.loadWallets();
		Thread.sleep(1500);
		assertNotNull("the only real seed copy is never deleted",
				firstXmrIdNamed("Acc-H"));
		java.util.Arrays.fill(seed, '\0');
	}

	private void writeRenameJournalRaw(String from, String toName, String to)
			throws Exception {
		String cur = store.readSettings();
		org.json.JSONObject o = cur == null ? new org.json.JSONObject()
				: new org.json.JSONObject(cur);
		org.json.JSONObject xmr = o.optJSONObject("xmr");
		if (xmr == null) xmr = new org.json.JSONObject();
		org.json.JSONObject rn = new org.json.JSONObject();
		rn.put("from", from);
		rn.put("toName", toName);
		if (to != null) rn.put("to", to);
		xmr.put("_rename", rn);
		o.put("xmr", xmr);
		store.writeSettings(o.toString());
	}

	private boolean pollUntilGone(String name, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (firstXmrIdNamed(name) == null) return true;
			Thread.sleep(200);
		}
		return firstXmrIdNamed(name) == null;
	}

	private String firstXmrIdNamed(String name) throws Exception {
		for (WalletRecord w : store.listWallets()) {
			if (w.name.equals(name)) return w.id;
		}
		return null;
	}

	private String pollForWalletId(String name, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			String id = firstXmrIdNamed(name);
			if (id != null) return id;
			Thread.sleep(200);
		}
		return null;
	}

	private String addressFromSeed(char[] seed) {
		MoneroEngine engine = new NativeMoneroEngine();
		File dir = new File(ctx.getCacheDir(), "det-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		MoneroEngine.Session s = engine.restore(new File(dir, "w")
				.getAbsolutePath(), "fp".toCharArray(), seed, 0, new char[0]);
		assertNotNull("engine restore for address derivation", s);
		assertEquals(0, s.status());
		String addr = s.address(0, 0);
		s.close();
		java.util.Arrays.fill(seed, '\0');
		deleteTree(dir);
		return addr;
	}

	private static void deleteTree(File dir) {
		File[] kids = dir.listFiles();
		if (kids != null) {
			for (File f : kids) {
				if (f.isDirectory()) deleteTree(f);
				else f.delete();
			}
		}
		dir.delete();
	}

	private File residueScanRoot() {
		return ctx.getFilesDir().getParentFile();
	}

	private boolean fileTreeContains(File root, byte[] needle) {
		if (root == null || needle.length == 0) return false;
		File[] kids = root.listFiles();
		if (kids == null) return false;
		for (File f : kids) {
			if (f.isDirectory()) {
				if (fileTreeContains(f, needle)) return true;
			} else if (fileContains(f, needle)) {
				return true;
			}
		}
		return false;
	}

	private boolean fileContains(File f, byte[] needle) {
		try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
			long len = raf.length();
			if (len <= 0 || len > 8L * 1024 * 1024) return false;
			byte[] data = new byte[(int) len];
			raf.readFully(data);
			return indexOf(data, needle) >= 0;
		} catch (Throwable t) {
			return false;
		}
	}

	private static int indexOf(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i <= hay.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	private void writeReport() {
		try {
			File out = new File(ctx.getExternalFilesDir(null),
					"xmr_p1_accept.txt");
			try (java.io.FileOutputStream fos =
						new java.io.FileOutputStream(out)) {
				fos.write(report.toString().getBytes(StandardCharsets.UTF_8));
			}
		} catch (Throwable ignored) {
		}
	}

	private interface Action {
		void run();
	}

	private char[] awaitSeed(XmrWalletManager m,
			LiveData<Event<XmrError>> errLd, Action action) {
		AtomicReference<String> out = new AtomicReference<>();
		AtomicReference<XmrError> err = new AtomicReference<>();
		latchOn(m.getSeedReveal(), errLd, action, ev -> out.set(ev),
				e -> err.set(e));
		String id = out.get();
		return id == null ? null : m.takePendingSeed(id);
	}

	private String awaitString(LiveData<Event<String>> ok,
			LiveData<Event<XmrError>> errLd, Action action) {
		AtomicReference<String> out = new AtomicReference<>();
		AtomicReference<XmrError> err = new AtomicReference<>();
		latchOn(ok, errLd, action, ev -> out.set(ev), e -> err.set(e));
		return out.get();
	}

	private <T> XmrError awaitError(LiveData<Event<T>> ok,
			LiveData<Event<XmrError>> errLd, Action action) {
		AtomicReference<T> out = new AtomicReference<>();
		AtomicReference<XmrError> err = new AtomicReference<>();
		latchOn(ok, errLd, action, ev -> out.set(ev), e -> err.set(e));
		return err.get();
	}

	private <T> void latchOn(LiveData<Event<T>> ok,
			LiveData<Event<XmrError>> errLd, Action action,
			Consumer<T> onOk, Consumer<XmrError> onErr) {
		CountDownLatch latch = new CountDownLatch(1);
		List<Observer<?>> registered = new ArrayList<>();
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Observer<Event<T>> obOk = ev -> {
				if (ev == null) return;
				T c = ev.getIfNotHandled();
				if (c != null) {
					onOk.accept(c);
					latch.countDown();
				}
			};
			Observer<Event<XmrError>> obErr = ev -> {
				if (ev == null) return;
				XmrError e = ev.getIfNotHandled();
				if (e != null) {
					onErr.accept(e);
					latch.countDown();
				}
			};
			ok.observeForever(obOk);
			errLd.observeForever(obErr);
			registered.add(obOk);
			registered.add(obErr);
		});
		InstrumentationRegistry.getInstrumentation().runOnMainSync(action::run);
		try {
			assertTrue("action timed out", latch.await(60, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				ok.removeObserver((Observer) registered.get(0));
				errLd.removeObserver((Observer) registered.get(1));
			});
		}
	}

	private interface Consumer<T> {
		void accept(T t);
	}
}
