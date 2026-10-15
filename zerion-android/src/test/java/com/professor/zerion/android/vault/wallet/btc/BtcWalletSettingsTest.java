package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.InMemoryVault;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BtcWalletSettingsTest {

	private final InMemoryVault vault;
	private final WalletStore store;
	private final BtcWalletSettings settings;

	public BtcWalletSettingsTest() throws Exception {
		vault = new InMemoryVault();
		store = new WalletStore(vault.manager);
		settings = new BtcWalletSettings(store);
	}

	@Test
	public void anAbsentDocumentReadsAsEmpty() throws Exception {
		assertNull(settings.readIfPresent());
		assertEquals(0, settings.read().length());
		assertEquals("EUR", settings.currency());
		assertEquals(0, settings.receiveIndex("w"));
		assertEquals("fallback", settings.displayName("w", "fallback"));
		assertEquals(0, settings.privacy("w").length());
		assertTrue(settings.pendingLog("w").all().isEmpty());
	}

	@Test
	public void indexesOnlyEverRise() throws Exception {
		settings.raiseReceiveIndex("w", 4);
		settings.raiseReceiveIndex("w", 2);
		settings.raiseChangeIndex("w", 1);
		assertEquals(4, settings.receiveIndex("w"));
		assertEquals(1, settings.changeIndex("w"));
		String stored = vault.list().get(0).name;
		settings.raiseReceiveIndex("w", 4);
		assertEquals("an index that does not rise is not written",
				stored, vault.list().get(0).name);
		settings.raiseReceiveIndex("w", 5);
		assertFalse(stored.equals(vault.list().get(0).name));
		settings.raiseReceiveIndex("w", 4);
		JSONObject o = settings.read();
		o.getJSONObject("recv").put("w", -7);
		settings.write(o);
		assertEquals(0, settings.receiveIndex("w"));
	}

	@Test
	public void walletRecordsMoveAndDropTogether()
			throws Exception {
		JSONObject o = new JSONObject();
		for (String section : new String[] {"privacy", "recv", "pending",
				"btc", "chg"}) {
			o.put(section, new JSONObject().put("old", section)
					.put("other", 1));
		}
		settings.write(o);
		settings.moveWalletRecords("old", "new");
		JSONObject moved = settings.read();
		for (String section : new String[] {"privacy", "recv", "pending",
				"btc", "chg"}) {
			assertEquals(section, moved.getJSONObject(section)
					.getString("new"));
			assertFalse(moved.getJSONObject(section).has("old"));
			assertEquals(1, moved.getJSONObject(section).getInt("other"));
		}
		settings.removeWalletRecords("new");
		settings.removeWalletRecords("old");
		JSONObject removed = settings.read();
		for (String section : new String[] {"privacy", "recv", "pending",
				"btc", "chg"}) {
			assertEquals("{\"other\":1}",
					removed.getJSONObject(section).toString());
		}
	}

	@Test
	public void concurrentWritersDoNotLoseUpdates() throws Exception {
		int threads = 4;
		int rounds = 25;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		List<Throwable> errors =
				Collections.synchronizedList(new ArrayList<>());
		for (int t = 0; t < threads; t++) {
			String id = "w" + t;
			int kind = t;
			new Thread(() -> {
				try {
					start.await();
					for (int i = 1; i <= rounds; i++) {
						int n = i;
						if (kind == 0) settings.raiseReceiveIndex(id, n);
						else if (kind == 1) settings.raiseChangeIndex(id, n);
						else if (kind == 2) {
							settings.mutatePrivacy(id, w -> w.put("n", n));
						} else {
							settings.saveDisplayName(id, "name" + n);
						}
					}
				} catch (Throwable e) {
					errors.add(e);
				} finally {
					done.countDown();
				}
			}).start();
		}
		start.countDown();
		assertTrue(done.await(120, TimeUnit.SECONDS));
		assertTrue(errors.toString(), errors.isEmpty());
		assertEquals(rounds, settings.receiveIndex("w0"));
		assertEquals(rounds, settings.changeIndex("w1"));
		assertEquals(rounds, settings.privacy("w2").getInt("n"));
		assertEquals("name" + rounds, settings.displayName("w3", ""));
	}

	@Test
	public void pendingEntriesKeepTheirShapeAndOrder() throws Exception {
		PendingLog log = settings.pendingLog("w");
		long now = System.currentTimeMillis();
		log.put(new PendingTx("a", "ta", "aa", Collections.singletonList(
				"x:0"), PendingTx.BROADCASTING, now, -5L));
		log.put(new PendingTx("b", "tb", "bb", Collections.emptyList(),
				PendingTx.SENT, now, 7L));
		JSONArray arr = settings.read().getJSONObject("pending")
				.getJSONArray("w");
		assertEquals(2, arr.length());
		assertEquals("a", arr.getJSONObject(0).getString("id"));
		assertEquals("b", arr.getJSONObject(1).getString("id"));
		assertTrue(settings.pendingLog("other").all().isEmpty());
		List<PendingTx> all = log.all();
		assertEquals(-5L, all.get(0).netSat);
		assertEquals(Collections.singletonList("x:0"), all.get(0).outpoints);
	}

	@Test
	public void cachedPriceAndCurrencyRoundTrip() throws Exception {
		settings.saveCurrency("CHF");
		assertEquals("CHF", settings.currency());
		BtcPrice.Rates r = BtcPrice.Rates.fromJson(
				"{\"EUR\":40000.0,\"USD\":45000.0}");
		long before = System.currentTimeMillis();
		settings.saveRates(r);
		BtcWalletSettings.CachedPrice cached = settings.cachedPrice();
		assertEquals(r.toJson(), cached.ratesJson);
		assertTrue(cached.at >= before);
		assertEquals("CHF", settings.read().getString("currency"));
	}
}
