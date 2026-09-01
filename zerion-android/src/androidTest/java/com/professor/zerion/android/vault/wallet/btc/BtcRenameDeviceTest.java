package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.ui.VaultViewModel;
import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Device proof that a BTC rename is a presentation-only change: the walletId
 * (vault item id) is immutable across rename, the seed is never re-encrypted,
 * and no per-wallet fund/privacy state is migrated. Mirrors the XMR rename
 * model. A throwaway vault in app-private storage is used; no network, no funds.
 */
@RunWith(AndroidJUnit4.class)
public class BtcRenameDeviceTest {

	private static final char[] VAULT_PW = "vault-master-pass".toCharArray();
	private static final char[] W_PW = "btc-wallet-pass".toCharArray();
	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";

	private Context ctx;
	private VaultManager vault;
	private WalletStore store;
	private VaultViewModel vm;

	@Before
	public void setUp() throws Exception {
		ctx = ApplicationProvider.getApplicationContext();
		deleteTree(new com.professor.zerion.android.vault.storage.SecureFileIO(ctx)
				.getVaultDir());
		vault = new VaultManager(ctx);
		if (!safeUnlock()) {
			vault.createVault(VAULT_PW.clone());
			assertTrue(safeUnlock());
		}
		store = new WalletStore(vault);
		vm = new VaultViewModel((Application) ctx.getApplicationContext(), vault,
				Runnable::run, store, 0);
	}

	private boolean safeUnlock() {
		try {
			return vault.unlockVault(VAULT_PW.clone());
		} catch (Exception e) {
			return false;
		}
	}

	private static void deleteTree(java.io.File f) {
		if (f == null || !f.exists()) return;
		java.io.File[] kids = f.listFiles();
		if (kids != null) for (java.io.File k : kids) deleteTree(k);
		f.delete();
	}

	private String createBtc(String name) throws Exception {
		return store.createWallet(WalletCoin.BTC, name, MNEMONIC.toCharArray(),
				W_PW.clone());
	}

	private String onlyBtcId() throws Exception {
		String id = null;
		for (WalletRecord w : store.listWallets()) {
			if (w.coin == WalletCoin.BTC) {
				if (id != null) fail("more than one BTC wallet");
				id = w.id;
			}
		}
		return id;
	}

	private long createdTsOf(String id) throws Exception {
		for (WalletRecord w : store.listWallets()) {
			if (w.id.equals(id)) return w.createdTimestamp;
		}
		fail("wallet id gone: " + id);
		return -1;
	}

	private org.json.JSONObject settings() throws Exception {
		String s = store.readSettings();
		return (s == null || s.isEmpty())
				? new org.json.JSONObject() : new org.json.JSONObject(s);
	}

	private String displayName(String id) throws Exception {
		org.json.JSONObject btc = settings().optJSONObject("btc");
		if (btc == null) return null;
		org.json.JSONObject w = btc.optJSONObject(id);
		if (w == null) return null;
		String nm = w.optString("nm", "");
		return nm.isEmpty() ? null : nm;
	}

	private void awaitDisplayName(String id, String expected) throws Exception {
		long deadline = System.currentTimeMillis() + 15000;
		while (System.currentTimeMillis() < deadline) {
			if (expected.equals(displayName(id))) return;
			Thread.sleep(50);
		}
		fail("rename did not persist display name '" + expected + "' for " + id);
	}

	private String seed(String id) throws Exception {
		char[] c = store.loadMnemonicChars(id, W_PW.clone());
		try {
			return new String(c);
		} finally {
			java.util.Arrays.fill(c, '\0');
		}
	}

	private void putSetting(String section, String id, org.json.JSONObject val)
			throws Exception {
		synchronized (store.settingsLock) {
			org.json.JSONObject o = settings();
			org.json.JSONObject sub = o.optJSONObject(section);
			if (sub == null) sub = new org.json.JSONObject();
			sub.put(id, val);
			o.put(section, sub);
			store.writeSettings(o.toString());
		}
	}

	private String getSetting(String section, String id) throws Exception {
		org.json.JSONObject sub = settings().optJSONObject(section);
		if (sub == null || !sub.has(id)) return null;
		return sub.get(id).toString();
	}

	@Test
	public void btcRenamePreservesWalletId() throws Exception {
		String id = createBtc("Alpha");
		vm.renameBtcWallet(id, "Beta", W_PW.clone());
		awaitDisplayName(id, "Beta");
		assertEquals("walletId is immutable across rename", id, onlyBtcId());
	}

	@Test
	public void btcRenameDoesNotResealSeed() throws Exception {
		String id = createBtc("Alpha");
		String before = seed(id);
		long ts = createdTsOf(id);
		vm.renameBtcWallet(id, "Beta", W_PW.clone());
		awaitDisplayName(id, "Beta");
		assertEquals("same walletId", id, onlyBtcId());
		assertEquals("vault item not recreated", ts, createdTsOf(id));
		assertEquals("same wallet password still opens the seed", before,
				seed(id));
		assertArrayEquals(before.toCharArray(), seed(id).toCharArray());
	}

	@Test
	public void btcRenamePreservesPendingReservations() throws Exception {
		String id = createBtc("Alpha");
		org.json.JSONObject pending = new org.json.JSONObject();
		pending.put("txid", "deadbeef");
		pending.put("state", "BROADCASTING");
		pending.put("outpoints", new org.json.JSONArray().put("abcd:0"));
		putSetting("pending", id, pending);
		String before = getSetting("pending", id);
		vm.renameBtcWallet(id, "Renamed", W_PW.clone());
		awaitDisplayName(id, "Renamed");
		assertEquals("pending reservations untouched by rename", before,
				getSetting("pending", id));
	}

	@Test
	public void btcRenamePreservesPrivacyMetadata() throws Exception {
		String id = createBtc("Alpha");
		org.json.JSONObject privacy = new org.json.JSONObject();
		privacy.put("extremeMode", true);
		privacy.put("frozen", new org.json.JSONArray().put("aaaa:1"));
		privacy.put("labels", new org.json.JSONObject().put("bbbb:2", "savings"));
		privacy.put("routing", "TOR");
		putSetting("privacy", id, privacy);
		putSetting("recv", id, new org.json.JSONObject());
		String beforePrivacy = getSetting("privacy", id);
		vm.renameBtcWallet(id, "Renamed", W_PW.clone());
		awaitDisplayName(id, "Renamed");
		assertEquals("privacy metadata untouched by rename", beforePrivacy,
				getSetting("privacy", id));
	}

	@Test
	public void repeatedRenamesKeepIdAndSeed() throws Exception {
		String id = createBtc("A");
		String seed = seed(id);
		vm.renameBtcWallet(id, "B", W_PW.clone());
		awaitDisplayName(id, "B");
		vm.renameBtcWallet(id, "C", W_PW.clone());
		awaitDisplayName(id, "C");
		vm.renameBtcWallet(id, "D", W_PW.clone());
		awaitDisplayName(id, "D");
		assertEquals("id constant across many renames", id, onlyBtcId());
		assertEquals("seed constant across many renames", seed, seed(id));
	}

	@Test
	public void renameSurvivesRestart() throws Exception {
		String id = createBtc("Alpha");
		vm.renameBtcWallet(id, "Persisted", W_PW.clone());
		awaitDisplayName(id, "Persisted");
		WalletStore reopened = new WalletStore(vault);
		org.json.JSONObject o = new org.json.JSONObject(reopened.readSettings());
		String nm = o.getJSONObject("btc").getJSONObject(id).getString("nm");
		assertEquals("display name persists across a fresh store", "Persisted",
				nm);
		assertEquals("id stable", id, onlyBtcId());
	}

	@Test
	public void renameAcceptsUnicodeAndAllowsDuplicateDisplayNames()
			throws Exception {
		String a = createBtc("First");
		String b = createBtc("Second");
		assertNotEquals(a, b);
		vm.renameBtcWallet(a, "Wallet 🦊 Ünïcödé",
				W_PW.clone());
		awaitDisplayName(a, "Wallet 🦊 Ünïcödé");
		vm.renameBtcWallet(b, "Wallet 🦊 Ünïcödé",
				W_PW.clone());
		awaitDisplayName(b, "Wallet 🦊 Ünïcödé");
		assertNotEquals("duplicate display names keep distinct ids", a, b);
		assertEquals(seed(a), seed(b));
	}

	@Test
	public void renameWithPendingAndHistoryMetadataHasNoFundSideEffect()
			throws Exception {
		String id = createBtc("Alpha");
		String seedBefore = seed(id);
		org.json.JSONObject pending = new org.json.JSONObject();
		pending.put("txid", "cafe");
		pending.put("outpoints", new org.json.JSONArray().put("ffff:0"));
		putSetting("pending", id, pending);
		org.json.JSONObject privacy = new org.json.JSONObject();
		privacy.put("labels", new org.json.JSONObject().put("ffff:0", "change"));
		putSetting("privacy", id, privacy);
		String pendingBefore = getSetting("pending", id);
		String privacyBefore = getSetting("privacy", id);
		vm.renameBtcWallet(id, "Beta", W_PW.clone());
		awaitDisplayName(id, "Beta");
		assertEquals(id, onlyBtcId());
		assertEquals("seed unchanged", seedBefore, seed(id));
		assertEquals("pending unchanged", pendingBefore, getSetting("pending", id));
		assertEquals("privacy unchanged", privacyBefore, getSetting("privacy", id));
	}
}
