package com.professor.zerion.android.vault.ui;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.btc.BtcPrice;
import com.professor.zerion.android.vault.wallet.btc.BtcWallet;
import com.professor.zerion.android.vault.wallet.btc.PendingLog;
import com.professor.zerion.android.vault.wallet.btc.PendingTx;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.vault.ui.VaultViewModelRig.await;
import static com.professor.zerion.android.vault.ui.VaultViewModelRig.field;
import static com.professor.zerion.android.vault.ui.VaultViewModelRig.settle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultWalletSettingsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String ABANDON = "abandon abandon abandon abandon"
			+ " abandon abandon abandon abandon abandon abandon abandon about";

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	@Test
	public void currencyDefaultsToEuroAndIsStoredUnderItsKey()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.loadWalletCurrency();
		settle();
		assertEquals("EUR", vm.getWalletCurrency().getValue());
		vm.saveWalletCurrency("USD");
		settle();
		assertEquals("USD", rig.settings().getString("currency"));
		VaultViewModel next = rig.newModel();
		next.loadWalletCurrency();
		settle();
		assertEquals("USD", next.getWalletCurrency().getValue());
	}

	@Test
	public void nodeListKeepsTheDefaultFirstAndStoresOnlyUserNodes()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String def = VaultViewModel.preferredDefaultNode();
		vm.loadNodes();
		settle();
		assertEquals(Collections.singletonList(def),
				vm.getWalletNodeList().getValue());
		assertEquals(def, vm.getWalletSelectedNode().getValue());

		vm.addNode("aaaa.onion:50001");
		vm.addNode("aaaa.onion:50001");
		vm.addNode(def);
		vm.addNode("10.0.0.2:50001");
		settle();
		assertEquals("[\"aaaa.onion:50001\",\"10.0.0.2:50001\"]",
				rig.settings().getJSONArray("nodes").toString());
		assertEquals(Arrays.asList(def, "aaaa.onion:50001",
				"10.0.0.2:50001"), vm.getWalletNodeList().getValue());

		vm.selectNode("aaaa.onion:50001");
		settle();
		assertEquals("aaaa.onion:50001", rig.settings().getString("node"));
		assertEquals("aaaa.onion:50001",
				vm.getWalletSelectedNode().getValue());

		vm.deleteNode("aaaa.onion:50001");
		settle();
		assertEquals("[\"10.0.0.2:50001\"]",
				rig.settings().getJSONArray("nodes").toString());
		assertEquals(def, rig.settings().getString("node"));
		assertEquals(def, vm.getWalletSelectedNode().getValue());

		String before = rig.settings().toString();
		int itemsBefore = rig.vault.size();
		vm.deleteNode(def);
		settle();
		assertEquals(before, rig.settings().toString());
		assertEquals(itemsBefore, rig.vault.size());
	}

	@Test
	public void legacyAndUnknownSelectionsFallBackToTheDefault()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String def = VaultViewModel.preferredDefaultNode();
		JSONObject o = new JSONObject();
		o.put("node", "electrum.blockstream.info:50001");
		rig.writeSettings(o);
		vm.loadNodes();
		settle();
		assertEquals(def, vm.getWalletSelectedNode().getValue());

		o.put("node", "zzzz.onion:1");
		o.put("nodes", new JSONArray());
		rig.writeSettings(o);
		vm.loadNodes();
		settle();
		assertEquals(def, vm.getWalletSelectedNode().getValue());
		assertEquals(Collections.singletonList(def),
				vm.getWalletNodeList().getValue());
	}

	@Test
	public void certificatePinsAreStoredPerNode() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.confirmNodePin("node.example:50002", "ab12");
		vm.confirmNodePin("other.example:50002", "cd34");
		settle();
		JSONObject pins = rig.settings().getJSONObject("pins");
		assertEquals("ab12", pins.getString("node.example:50002"));
		assertEquals("cd34", pins.getString("other.example:50002"));
		assertEquals(2, pins.length());
	}

	@Test
	public void cachedPriceIsReadBackWithItsAge() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		JSONObject o = new JSONObject();
		o.put("priceRates", "{\"EUR\":50000.0,\"USD\":60000.0}");
		o.put("priceAt", System.currentTimeMillis());
		rig.writeSettings(o);
		vm.loadCachedPrice();
		settle();
		BtcPrice.Rates r = vm.getBtcRates().getValue();
		assertNotNull(r);
		assertEquals(50000.0, r.eur, 0);
		assertEquals(60000.0, r.usd, 0);
		assertEquals(Boolean.FALSE, vm.getPriceStale().getValue());

		o.put("priceAt", System.currentTimeMillis() - 16L * 60L * 1000L);
		rig.writeSettings(o);
		VaultViewModel stale = rig.newModel();
		stale.loadCachedPrice();
		settle();
		assertEquals(Boolean.TRUE, stale.getPriceStale().getValue());

		o.put("priceRates", "");
		rig.writeSettings(o);
		VaultViewModel none = rig.newModel();
		none.loadCachedPrice();
		settle();
		assertNull(none.getBtcRates().getValue());
	}

	@Test
	public void createdWalletIsAVaultItemAndRevealsItsSeed()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = rig.createWallet(vm, "first");
		VaultItem item = rig.vault.named("BTC\nfirst");
		assertNotNull(item);
		assertEquals(id, item.id);
		assertEquals(VaultItem.ItemType.WALLET, item.type);
		assertEquals(1, rig.vault.raw(id)[0]);
		char[] seed = vm.getWalletSeedReveal().getValue();
		assertNotNull(seed);
		assertEquals(24, new String(seed).split(" ").length);
		List<WalletRecord> listed = vm.getWallets().getValue();
		assertEquals(1, listed.size());
		assertEquals("first", listed.get(0).name);
	}

	@Test
	public void renameStoresADisplayNameAndLeavesTheItemName()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = rig.createWallet(vm, "first");
		vm.renameBtcWallet(id, "renamed", new char[0]);
		settle();
		assertEquals("renamed", rig.settings().getJSONObject("btc")
				.getJSONObject(id).getString("nm"));
		assertNotNull(rig.vault.named("BTC\nfirst"));
		assertEquals("renamed", vm.getWallets().getValue().get(0).name);
		VaultViewModel next = rig.newModel();
		next.loadWallets();
		settle();
		assertEquals("renamed", next.getWallets().getValue().get(0).name);
	}

	@Test
	public void passwordChangeMovesTheWalletRecordsToTheNewWallet()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = rig.createWallet(vm, "first");
		vm.renameBtcWallet(id, "shown", new char[0]);
		vm.setWalletRouting(id, VaultViewModel.ROUTING_LOCAL);
		settle();
		JSONObject o = rig.settings();
		o.put("recv", new JSONObject().put(id, 5).put("keep", 1));
		o.put("chg", new JSONObject().put(id, 7));
		o.put("pending", new JSONObject().put(id, new JSONArray()));
		rig.writeSettings(o);

		vm.changeBtcWalletPassword(id, "first", new char[0],
				"new-password".toCharArray());
		settle();
		List<WalletRecord> wallets = rig.store.listWallets();
		assertEquals(1, wallets.size());
		String moved = wallets.get(0).id;
		assertFalse(moved.equals(id));
		assertTrue(wallets.get(0).hasPassword);
		JSONObject after = rig.settings();
		for (String section : new String[] {"privacy", "recv", "chg",
				"pending", "btc"}) {
			assertFalse(section, after.getJSONObject(section).has(id));
			assertTrue(section, after.getJSONObject(section).has(moved));
		}
		assertEquals(5, after.getJSONObject("recv").getInt(moved));
		assertEquals(1, after.getJSONObject("recv").getInt("keep"));
		assertEquals(VaultViewModel.ROUTING_LOCAL,
				vm.readWalletRouting(moved));
		assertEquals("the change index moves with the wallet",
				7, after.getJSONObject("chg").getInt(moved));
	}

	@Test
	public void deletionNeedsTheWalletCredentialAndDropsTheRecords()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "section-pass");
		String id = rig.createWallet(vm, "first");
		String other = rig.createWallet(vm, "second");
		vm.renameBtcWallet(id, "shown", new char[0]);
		vm.setWalletRouting(id, VaultViewModel.ROUTING_DIRECT);
		vm.setWalletRouting(other, VaultViewModel.ROUTING_LOCAL);
		settle();
		JSONObject o = rig.settings();
		o.put("recv", new JSONObject().put(id, 2));
		o.put("chg", new JSONObject().put(id, 3));
		o.put("pending", new JSONObject().put(id, new JSONArray()));
		rig.writeSettings(o);

		vm.deleteWalletWithAuth(id, "wrong".toCharArray());
		settle();
		assertNotNull(rig.vault.named("BTC\nfirst"));
		assertEquals(rig.app.getString(R.string.wallet_wrong_password),
				vm.getWalletError().getValue().getIfNotHandled());

		vm.deleteWalletWithAuth(id, "section-pass".toCharArray());
		settle();
		assertNull(rig.vault.named("BTC\nfirst"));
		assertEquals(id, vm.getWalletDeleted().getValue().getIfNotHandled());
		JSONObject after = rig.settings();
		for (String section : new String[] {"privacy", "recv", "chg",
				"pending", "btc"}) {
			assertFalse(section, after.getJSONObject(section).has(id));
		}
		assertEquals(VaultViewModel.ROUTING_LOCAL,
				vm.readWalletRouting(other));
		assertEquals(1, vm.getWallets().getValue().size());
	}

	@Test
	public void routingDefaultsToTorAndRejectsUnknownModes()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		assertEquals(VaultViewModel.ROUTING_TOR, vm.readWalletRouting("w1"));
		vm.setWalletRouting("w1", VaultViewModel.ROUTING_DIRECT);
		settle();
		assertEquals(VaultViewModel.ROUTING_DIRECT,
				vm.readWalletRouting("w1"));
		assertEquals("direct", rig.settings().getJSONObject("privacy")
				.getJSONObject("w1").getString("routing"));
		vm.setWalletRouting("w1", "bogus");
		settle();
		assertEquals("bogus", rig.settings().getJSONObject("privacy")
				.getJSONObject("w1").getString("routing"));
		assertEquals(VaultViewModel.ROUTING_TOR, vm.readWalletRouting("w1"));
	}

	@Test
	public void openWalletRestoresTheReceiveIndexAndRaisesItOnly()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "section-pass");
		String id = rig.importWallet(vm, "known", ABANDON);
		JSONObject o = rig.settings();
		o.put("recv", new JSONObject().put(id, 3));
		rig.writeSettings(o);
		rig.open(vm, id);
		BtcWallet w = (BtcWallet) field(vm, "openBtc");
		assertNotNull(w);
		assertEquals(w.receiveAddressAt(3),
				vm.getBtcReceiveAddress().getValue());
		vm.newReceiveAddress();
		settle();
		assertEquals(4, rig.settings().getJSONObject("recv").getInt(id));
		assertEquals(w.receiveAddressAt(4),
				vm.getBtcReceiveAddress().getValue());
		vm.closeBtcWallet();
		assertNull(field(vm, "openBtc"));
	}

	@Test
	public void privacyTogglesAreStoredPerWallet() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "section-pass");
		String id = rig.importWallet(vm, "known", ABANDON);
		rig.open(vm, id);
		assertFalse(vm.isExtremeMode(id));
		assertFalse(vm.isSpEnabled(id));
		vm.setExtremeMode(true);
		vm.setSpEnabled(true);
		settle();
		JSONObject p = rig.settings().getJSONObject("privacy")
				.getJSONObject(id);
		assertTrue(p.getBoolean("extremeMode"));
		assertTrue(p.getBoolean("spEnabled"));
		assertTrue(vm.isExtremeMode(id));
		assertTrue(vm.isSpEnabled(id));
		assertEquals(Boolean.TRUE, vm.getWalletExtremeMode().getValue());
		assertEquals(Boolean.TRUE, vm.getWalletSpEnabled().getValue());
		vm.setExtremeMode(false);
		settle();
		assertFalse(vm.isExtremeMode(id));
	}

	@Test
	public void silentPaymentOracleMustUseHttpsAndMovesTheScanCursor()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "section-pass");
		String id = rig.importWallet(vm, "known", ABANDON);
		rig.open(vm, id);
		vm.getWalletError().getValue().getIfNotHandled();
		vm.saveSpConfig("HTTP://oracle.example", 10);
		settle();
		assertEquals(rig.app.getString(R.string.wallet_sp_oracle_https),
				vm.getWalletError().getValue().getIfNotHandled());
		assertFalse(rig.settings().optJSONObject("privacy") != null
				&& rig.settings().getJSONObject("privacy")
				.getJSONObject(id).has("spOracle"));

		vm.saveSpConfig("  https://oracle.example  ", 100);
		settle();
		JSONObject p = rig.settings().getJSONObject("privacy")
				.getJSONObject(id);
		assertEquals("https://oracle.example", p.getString("spOracle"));
		assertEquals(100, p.getInt("spBirthday"));
		assertEquals(99, p.getInt("spScanned"));
		long[] info = vm.getSpInfo().getValue();
		assertNotNull(info);
		assertEquals(Arrays.toString(new long[] {0, 0, 99, 1, 100}),
				Arrays.toString(info));

		vm.saveSpConfig("https://oracle.example", 50);
		settle();
		p = rig.settings().getJSONObject("privacy").getJSONObject(id);
		assertEquals(50, p.getInt("spBirthday"));
		assertEquals(99, p.getInt("spScanned"));
	}

	@Test
	public void storedSilentPaymentCoinsAreCountedForTheOpenWallet()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "section-pass");
		String id = rig.importWallet(vm, "known", ABANDON);
		rig.open(vm, id);
		JSONArray coins = new JSONArray();
		coins.put(new JSONObject().put("t", "aa").put("v", 1)
				.put("val", 5000L).put("x", "0f10").put("w", "ff00"));
		coins.put(new JSONObject().put("t", "bb").put("v", 0)
				.put("val", 700L).put("x", "01").put("w", "02"));
		JSONObject o = rig.settings();
		o.put("privacy", new JSONObject().put(id, new JSONObject()
				.put("spUtxos", coins).put("spScanned", 12)));
		rig.writeSettings(o);
		vm.loadSp();
		settle();
		assertEquals(Arrays.toString(new long[] {5700, 2, 12, 0, 0}),
				Arrays.toString(vm.getSpInfo().getValue()));
		vm.closeBtcWallet();
	}

	@Test
	public void coinControlRecordsLiveInThePrivacyRecord() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "section-pass");
		String id = rig.importWallet(vm, "known", ABANDON);
		rig.open(vm, id);
		vm.setUtxoFrozen("aa:0", true);
		vm.setUtxoFrozen("bb:1", true);
		vm.setUtxoFrozen("aa:0", false);
		vm.setUtxoLabel("cc:2", "rent");
		vm.setUtxoLabel("dd:3", "gone");
		vm.setUtxoLabel("dd:3", "");
		settle();
		JSONObject p = rig.settings().getJSONObject("privacy")
				.getJSONObject(id);
		assertEquals("[\"bb:1\"]", p.getJSONArray("frozen").toString());
		assertEquals("{\"cc:2\":\"rent\"}",
				p.getJSONObject("labels").toString());

		BtcWallet w = (BtcWallet) field(vm, "openBtc");
		PrivacyStore store = (PrivacyStore) field(w, "privacyStore");
		store.putOriginHint("bc1qaddress", "cluster-1");
		Set<String> frozen = store.frozen();
		Map<String, String> labels = store.labels();
		Map<String, String> hints = store.originHints();
		assertEquals(Collections.singleton("bb:1"), frozen);
		assertEquals(Collections.singletonMap("cc:2", "rent"), labels);
		assertEquals(Collections.singletonMap("bc1qaddress", "cluster-1"),
				hints);
		assertEquals("cluster-1", rig.settings().getJSONObject("privacy")
				.getJSONObject(id).getJSONObject("originHints")
				.getString("bc1qaddress"));
	}

	@Test
	public void pendingSendsAreStoredPerWalletAndOldResolvedOnesPruned()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "section-pass");
		String id = rig.importWallet(vm, "known", ABANDON);
		rig.open(vm, id);
		BtcWallet w = (BtcWallet) field(vm, "openBtc");
		PendingLog log = (PendingLog) field(w, "pendingLog");
		long now = System.currentTimeMillis();
		long old = now - 8L * 24L * 3600L * 1000L;
		log.put(new PendingTx("p1", "t1", "00ff", Arrays.asList("a:0",
				"b:1"), PendingTx.BROADCASTING, now, -1500L));
		JSONObject first = rig.settings().getJSONObject("pending")
				.getJSONArray(id).getJSONObject(0);
		assertEquals("p1", first.getString("id"));
		assertEquals("t1", first.getString("txid"));
		assertEquals("00ff", first.getString("rawHex"));
		assertEquals(PendingTx.BROADCASTING, first.getString("state"));
		assertEquals(now, first.getLong("createdAt"));
		assertEquals(-1500L, first.getLong("netSat"));
		assertEquals("[\"a:0\",\"b:1\"]",
				first.getJSONArray("outpoints").toString());
		assertEquals(7, first.length());

		log.put(new PendingTx("p1", "t1", "00ff", Arrays.asList("a:0",
				"b:1"), PendingTx.SENT, now, -1500L));
		log.put(new PendingTx("p2", "t2", "11", Collections.emptyList(),
				PendingTx.SETTLED, old, 10L));
		log.put(new PendingTx("p3", "t3", "22", Collections.emptyList(),
				PendingTx.FAILED, old, 20L));
		log.put(new PendingTx("p4", "t4", "33", Collections.emptyList(),
				PendingTx.SETTLED, now, 30L));
		log.put(new PendingTx("p5", "t5", "44", Collections.emptyList(),
				PendingTx.POSSIBLY_SENT, old, 40L));
		List<PendingTx> before = log.all();
		assertEquals(3, before.size());
		assertEquals("p4", before.get(1).id);
		assertEquals("p5", before.get(2).id);
		log.put(new PendingTx("p6", "t6", "55", Collections.emptyList(),
				PendingTx.BROADCASTING, now, 50L));
		List<PendingTx> all = log.all();
		StringBuilder ids = new StringBuilder();
		for (PendingTx t : all) ids.append(t.id).append(' ');
		assertEquals("p1 p4 p5 p6 ", ids.toString());
		assertEquals(PendingTx.SENT, all.get(0).state);
		assertEquals(Arrays.asList("a:0", "b:1"), all.get(0).outpoints);
	}
}
