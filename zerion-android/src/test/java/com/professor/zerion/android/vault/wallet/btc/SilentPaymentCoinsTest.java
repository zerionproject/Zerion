package com.professor.zerion.android.vault.wallet.btc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SilentPaymentCoinsTest {

	private static SilentPaymentScanner.Found coin(String txid, int vout,
			long value) {
		byte[] x = new byte[32];
		Arrays.fill(x, (byte) 0xAB);
		byte[] w = new byte[32];
		Arrays.fill(w, (byte) 0x0C);
		return new SilentPaymentScanner.Found(txid, vout, value, x, w);
	}

	private static JSONArray stored() throws Exception {
		JSONObject u = new JSONObject();
		u.put("t", "aa");
		u.put("v", 1);
		u.put("val", 5000L);
		u.put("x", "0f10");
		u.put("w", "ff00");
		return new JSONArray().put(u);
	}

	@Test
	public void storedCoinsLoadAndNewOnesAreAddedOnce() throws Exception {
		SilentPaymentCoins coins = new SilentPaymentCoins();
		coins.load("w1", stored());
		List<SilentPaymentScanner.Found> loaded = coins.snapshot("w1");
		assertEquals(1, loaded.size());
		assertArrayEquals(new byte[] {0x0f, 0x10}, loaded.get(0).xonly);
		assertArrayEquals(new byte[] {(byte) 0xff, 0x00}, loaded.get(0).tweak);
		JSONArray json = coins.addFound("w1", Arrays.asList(
				coin("aa", 1, 5000), coin("bb", 0, 700)));
		assertNotNull(json);
		assertEquals(2, json.length());
		JSONObject kept = json.getJSONObject(0);
		assertEquals(5, kept.length());
		assertEquals("aa", kept.getString("t"));
		assertEquals(1, kept.getInt("v"));
		assertEquals(5000L, kept.getLong("val"));
		assertEquals("0f10", kept.getString("x"));
		assertEquals("ff00", kept.getString("w"));
		JSONObject added = json.getJSONObject(1);
		assertEquals("bb", added.getString("t"));
		assertEquals(0, added.getInt("v"));
		assertEquals(700L, added.getLong("val"));
		assertEquals("abababababababababababababababababababababababababababab"
				+ "abababab", added.getString("x"));
		assertEquals(Arrays.toString(new long[] {5700, 2}),
				Arrays.toString(coins.totals()));
	}

	@Test
	public void coinsOfAnotherWalletAreNeverHandedOut() throws Exception {
		SilentPaymentCoins coins = new SilentPaymentCoins();
		coins.load("w1", stored());
		assertTrue(coins.snapshot("w2").isEmpty());
		assertNull("a scan of another wallet is refused",
				coins.addFound("w2", Collections.singletonList(
						coin("cc", 0, 1))));
		assertEquals(1, coins.snapshot("w1").size());
		coins.clear();
		assertTrue(coins.snapshot("w1").isEmpty());
		assertNotNull(coins.addFound("w2", Collections.singletonList(
				coin("cc", 0, 1))));
		assertEquals(1, coins.snapshot("w2").size());
	}

	@Test
	public void aSweepEmptiesTheCoinsButKeepsTheWallet() throws Exception {
		SilentPaymentCoins coins = new SilentPaymentCoins();
		coins.load("w1", stored());
		coins.spent();
		assertTrue(coins.snapshot("w1").isEmpty());
		assertNull(coins.addFound("w2", Collections.emptyList()));
		assertNotNull(coins.addFound("w1", Collections.emptyList()));
	}

	@Test
	public void scansSweepsAndClosesOnSeveralThreadsDoNotCorruptTheList()
			throws Exception {
		SilentPaymentCoins coins = new SilentPaymentCoins();
		JSONArray base = stored();
		int rounds = 2000;
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		List<Thread> threads = new ArrayList<>();
		threads.add(new Thread(() -> run(start, error, () -> {
			for (int i = 0; i < rounds; i++) {
				coins.addFound("w1", Arrays.asList(coin("s" + (i % 50), 0, 1),
						coin("t" + i, 1, 2)));
			}
		})));
		threads.add(new Thread(() -> run(start, error, () -> {
			for (int i = 0; i < rounds; i++) {
				for (SilentPaymentScanner.Found f : coins.snapshot("w1")) {
					assertNotNull(f.txid);
				}
				coins.totals();
			}
		})));
		threads.add(new Thread(() -> run(start, error, () -> {
			for (int i = 0; i < rounds; i++) {
				if (i % 3 == 0) coins.clear();
				else if (i % 3 == 1) coins.load("w1", base);
				else coins.spent();
			}
		})));
		for (Thread t : threads) t.start();
		start.countDown();
		for (Thread t : threads) t.join(120_000);
		assertNull(String.valueOf(error.get()), error.get());
		coins.load("w1", base);
		assertEquals(1, coins.snapshot("w1").size());
	}

	private interface Work {
		void run() throws Exception;
	}

	private static void run(CountDownLatch start,
			AtomicReference<Throwable> error, Work work) {
		try {
			start.await(30, TimeUnit.SECONDS);
			work.run();
		} catch (Throwable t) {
			error.compareAndSet(null, t);
		}
	}
}
