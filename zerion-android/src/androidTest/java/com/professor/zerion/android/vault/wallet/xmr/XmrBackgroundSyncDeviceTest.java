package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * On-device proof of the two-layer key boundary the background-sync bridge
 * provides: the main keys file carries the spend key and requires the wallet
 * password, while the separate view-only background keys file, encrypted under
 * a distinct (vault-tier) password, opens a spend-keyless wallet. No daemon and
 * no network. The wallet is a throwaway in the test cache directory; no real
 * vault is touched. This validates the storage invariant that decrypting the
 * vault-tier layer alone cannot yield XMR spending authority.
 */
@RunWith(AndroidJUnit4.class)
public class XmrBackgroundSyncDeviceTest {

	private static final char[] WALLET_PW = "wallet-spend-pass".toCharArray();
	private static final char[] BG_PW = "vault-tier-bg-pass".toCharArray();
	private static final char[] WRONG = "wrong".toCharArray();

	private static int words(char[] seed) {
		if (seed == null) return 0;
		String s = new String(seed).trim();
		return s.isEmpty() ? 0 : s.split("\\s+").length;
	}

	@Test
	public void vaultTierBackgroundFileHasNoSpendKey() {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getCacheDir(), "xmr-bgsec-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		String base = new File(dir, "w").getAbsolutePath();
		MoneroEngine engine = new NativeMoneroEngine();
		boolean ok = false;
		try {
			MoneroEngine.Session s = engine.create(base, WALLET_PW, "English");
			assertNotNull("create must yield a session", s);
			assertTrue("a freshly created wallet is spend-capable (25-word seed)",
					words(s.seed(new char[0])) == 25);
			assertFalse("a normal wallet is not a background wallet",
					s.isBackgroundWallet());

			assertTrue("setupBackgroundSync must succeed",
					s.setupBackgroundSync(WALLET_PW, BG_PW));
			assertTrue(s.store(base));

			MoneroEngine.Session sync = s;
			assertTrue("startBackgroundSync must succeed",
					sync.startBackgroundSync());
			assertTrue("the wallet reports background syncing",
					sync.isBackgroundSyncing());
			assertTrue("stopBackgroundSync restores with the wallet password",
					sync.stopBackgroundSync(WALLET_PW));
			s.close();

			File bg = findBackgroundFile(dir);
			assertNotNull("a separate background keys file must be written", bg);

			MoneroEngine.Session main = engine.open(base, WALLET_PW);
			assertNotNull("the main file opens with the wallet password", main);
			assertTrue("the wallet password opens the main file cleanly",
					main.status() == 0);
			assertFalse(main.isBackgroundWallet());
			assertTrue("the main file exposes the spend key (25-word seed)",
					words(main.seed(new char[0])) == 25);
			main.close();

			assertTrue("the wrong password must not yield a spend-capable "
					+ "main wallet", notSpendable(engine, base, WRONG));

			String bgBase = bg.getAbsolutePath().endsWith(".keys")
					? bg.getAbsolutePath().substring(0,
							bg.getAbsolutePath().length() - 5)
					: bg.getAbsolutePath();
			MoneroEngine.Session view = engine.open(bgBase, BG_PW);
			assertNotNull("the background file opens with the vault-tier password",
					view);
			assertTrue("the vault-tier password opens the background file cleanly",
					view.status() == 0);
			assertTrue("the background file is a background (view-only) wallet",
					view.isBackgroundWallet());
			assertTrue("the vault-tier background file exposes NO spend key: "
					+ "decrypting the vault layer alone cannot spend",
					words(view.seed(new char[0])) == 0);
			view.close();

			assertTrue("the background file must not open without its password",
					notSpendable(engine, bgBase, WRONG));

			ok = true;
		} finally {
			if (ok) {
				File[] files = dir.listFiles();
				if (files != null) for (File f : files) f.delete();
				dir.delete();
			}
		}
	}

	/**
	 * Store-1 closure at rest, exercising the real wallet key derivation exactly
	 * as the manager does. The main keys file is encrypted under a wallet-
	 * password-derived key; the vault-tier layer holds only the random background
	 * credential and the salt. Proves attacker model B: a party holding the
	 * vault-tier material (background credential + salt) but not the wallet
	 * password can open only the view-only background wallet and can never open a
	 * spend-capable main wallet, while the correct wallet password can.
	 */
	@Test
	public void vaultTierMaterialAloneCannotSpend() {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getCacheDir(), "xmr-store1-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		String base = new File(dir, "w").getAbsolutePath();
		MoneroEngine engine = new NativeMoneroEngine();
		boolean ok = false;
		try {
			byte[] salt = XmrWalletKek.newSalt();
			char[] mainPw = XmrWalletKek.deriveMainFilePassword(WALLET_PW, salt);
			char[] bgPw = "vault-tier-random-bg".toCharArray();

			MoneroEngine.Session s = engine.create(base, mainPw, "English");
			assertNotNull(s);
			assertTrue(words(s.seed(new char[0])) == 25);
			assertTrue(s.setupBackgroundSync(mainPw, bgPw));
			assertTrue(s.store(base));
			s.close();

			String bgBase = new File(dir, "w.background").getAbsolutePath();

			MoneroEngine.Session view = engine.open(bgBase, bgPw);
			assertNotNull(view);
			assertTrue(view.isBackgroundWallet());
			assertTrue("vault-tier open is view-only, no spend key",
					words(view.seed(new char[0])) == 0);
			view.close();

			assertTrue("the vault-tier background credential must not open the "
					+ "spend-capable main wallet",
					notSpendable(engine, base, bgPw));

			char[] correct = XmrWalletKek.deriveMainFilePassword(WALLET_PW, salt);
			MoneroEngine.Session spend = engine.open(base, correct);
			assertNotNull(spend);
			assertTrue(spend.status() == 0);
			assertFalse(spend.isBackgroundWallet());
			assertTrue("the wallet-password-derived key opens the spend wallet",
					words(spend.seed(new char[0])) == 25);
			spend.close();
			java.util.Arrays.fill(correct, '\0');

			char[] wrong = XmrWalletKek.deriveMainFilePassword(
					"not-the-wallet-password".toCharArray(), salt);
			assertTrue("a wrong wallet password derives a key that cannot spend",
					notSpendable(engine, base, wrong));
			java.util.Arrays.fill(wrong, '\0');
			java.util.Arrays.fill(mainPw, '\0');

			ok = true;
		} finally {
			if (ok) {
				File[] files = dir.listFiles();
				if (files != null) for (File f : files) f.delete();
				dir.delete();
			}
		}
	}

	@Test
	public void backgroundWalletSupportsSubaddressGeneration() {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getCacheDir(), "xmr-bgsub-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		String base = new File(dir, "w").getAbsolutePath();
		MoneroEngine engine = new NativeMoneroEngine();
		boolean ok = false;
		try {
			char[] bgPw = "vault-tier-bg".toCharArray();
			MoneroEngine.Session s = engine.create(base, WALLET_PW, "English");
			assertNotNull(s);
			assertTrue(s.setupBackgroundSync(WALLET_PW, bgPw));
			assertTrue(s.store(base));
			s.close();

			MoneroEngine.Session bg = engine.open(
					new File(dir, "w.background").getAbsolutePath(), bgPw);
			assertNotNull(bg);
			assertTrue(bg.isBackgroundWallet());

			int target = 8;
			long count = bg.numSubaddresses(0);
			int guard = 0;
			while (count <= target) {
				bg.addSubaddress(0, "");
				long next = bg.numSubaddresses(0);
				if (next <= count) break;
				count = next;
				if (++guard > 1000) {
					throw new AssertionError("subaddress growth did not "
							+ "terminate on a background wallet");
				}
			}

			for (int i = 0; i <= target; i++) {
				String a = bg.address(0, i);
				assertNotNull("background wallet must derive address(0," + i + ")",
						a);
				assertTrue("address(0," + i + ") must be a full address",
						a.length() > 90);
			}
			bg.close();
			ok = true;
		} finally {
			if (ok) {
				File[] files = dir.listFiles();
				if (files != null) for (File f : files) f.delete();
				dir.delete();
			}
		}
	}

	/**
	 * True when opening {@code base} with {@code password} does not yield a
	 * spend-capable wallet: either the open reports an error status, or the
	 * opened wallet has no 25-word seed. wallet2_api returns a non-null wallet
	 * on a bad password and signals failure through the status, so a null check
	 * alone is not enough.
	 */
	private static boolean notSpendable(MoneroEngine engine, String base,
			char[] password) {
		MoneroEngine.Session s = engine.open(base, password);
		if (s == null) return true;
		try {
			if (s.status() != 0) return true;
			return words(s.seed(new char[0])) != 25;
		} finally {
			s.close();
		}
	}

	/**
	 * The balance-convergence plumbing used after a send: an open background
	 * wallet holds the background keys-file lock, so it is closed before the
	 * spend wallet writes its post-relay state back into w.background (a store on
	 * the CustomPassword main wallet updates the background cache), and the
	 * background wallet then reopens cleanly from that updated cache. Proven on a
	 * throwaway wallet (no funds, so no spend to propagate); the spent-output
	 * propagation itself is guaranteed by wallet2 (commit_tx set_spent +
	 * store_background_cache). This asserts there is no keys-file lock conflict
	 * and no cache corruption.
	 */
	@Test
	public void spendStoreUpdatesBackgroundCacheAfterReleasingLock() {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getCacheDir(), "xmr-conv-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		String base = new File(dir, "w").getAbsolutePath();
		String bgPath = new File(dir, "w.background").getAbsolutePath();
		MoneroEngine engine = new NativeMoneroEngine();
		try {
			MoneroEngine.Session spend = engine.create(base, WALLET_PW,
					"English");
			assertNotNull(spend);
			assertTrue(spend.setupBackgroundSync(WALLET_PW, BG_PW));
			assertTrue(spend.store(base));
			spend.close();

			MoneroEngine.Session bg = engine.open(bgPath, BG_PW);
			assertNotNull("background view wallet opens", bg);
			assertTrue(bg.isBackgroundWallet());
			long bal = bg.balance(0);
			bg.close();

			MoneroEngine.Session main = engine.open(base, WALLET_PW);
			assertNotNull(main);
			assertFalse(main.isBackgroundWallet());
			assertTrue("spend store updates w.background with no lock conflict",
					main.store(""));
			main.close();

			MoneroEngine.Session bg2 = engine.open(bgPath, BG_PW);
			assertNotNull("background reopens from the updated cache", bg2);
			assertTrue(bg2.isBackgroundWallet());
			assertEquals(0, bg2.status());
			assertEquals("balance stays consistent", bal, bg2.balance(0));
			bg2.close();
		} finally {
			File[] fs = dir.listFiles();
			if (fs != null) for (File f : fs) f.delete();
			dir.delete();
		}
	}

	/**
	 * The external-spend reconciliation sequence, exactly as
	 * {@code XmrWalletManager.reconcileExternalSpends} runs it: with no view
	 * session holding the cache, open the spend wallet purely locally (no
	 * init/connect, so no daemon), which triggers wallet2
	 * process_background_cache_on_open; store it, regenerating w.background with
	 * any resolved spent flags; then reopen the view. On a throwaway wallet there
	 * is no external spend to resolve (no funds, no scanned plausible spend), so
	 * this asserts the reconciliation is a safe local no-op: the spend wallet
	 * opens and stores offline without a lock conflict, and the reopened view is
	 * still a spend-keyless background wallet with a consistent balance. The
	 * resolution of a real external spend is guaranteed by wallet2 (the replayed
	 * plausible spend resolves its key image and set_spent under the spend key);
	 * it cannot be funded here.
	 */
	@Test
	public void reconcileOpensSpendWalletLocallyAndKeepsTheViewSpendKeyless() {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getCacheDir(), "xmr-recon-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		String base = new File(dir, "w").getAbsolutePath();
		String bgPath = new File(dir, "w.background").getAbsolutePath();
		MoneroEngine engine = new NativeMoneroEngine();
		try {
			MoneroEngine.Session create = engine.create(base, WALLET_PW,
					"English");
			assertNotNull(create);
			assertTrue(create.setupBackgroundSync(WALLET_PW, BG_PW));
			assertTrue(create.store(base));
			create.close();

			MoneroEngine.Session view0 = engine.open(bgPath, BG_PW);
			assertNotNull(view0);
			assertTrue(view0.isBackgroundWallet());
			long before = view0.balance(0);
			view0.close();

			MoneroEngine.Session spend = engine.open(base, WALLET_PW);
			assertNotNull("spend wallet opens offline for reconciliation", spend);
			assertEquals(0, spend.status());
			assertFalse("reconciliation opens the spend-capable wallet",
					spend.isBackgroundWallet());
			assertTrue("local store regenerates w.background", spend.store(""));
			spend.close();

			MoneroEngine.Session view1 = engine.open(bgPath, BG_PW);
			assertNotNull("view reopens from the reconciled cache", view1);
			assertTrue("runtime view stays spend-keyless (Store-1)",
					view1.isBackgroundWallet());
			assertEquals(0, view1.status());
			char[] viewSeed = view1.seed(new char[0]);
			assertEquals("view wallet exposes no spend seed", 0, words(viewSeed));
			if (viewSeed != null) java.util.Arrays.fill(viewSeed, '\0');
			assertEquals("balance stays consistent with no external spend",
					before, view1.balance(0));
			view1.close();
		} finally {
			File[] fs = dir.listFiles();
			if (fs != null) for (File f : fs) f.delete();
			dir.delete();
		}
	}

	private static File findBackgroundFile(File dir) {
		File[] files = dir.listFiles();
		if (files == null) return null;
		File keys = null;
		File any = null;
		for (File f : files) {
			String n = f.getName();
			if (n.contains(".background")) {
				if (n.endsWith(".keys")) keys = f;
				else any = f;
			}
		}
		return keys != null ? keys : any;
	}
}
