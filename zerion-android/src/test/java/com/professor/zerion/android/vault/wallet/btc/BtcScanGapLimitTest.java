package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.IOException;

public class BtcScanGapLimitTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String TXID =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static BtcWallet wallet(FakeElectrum e) {
		return new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
	}

	/**
	 * BTC-08: a server answering non-empty history for every script hash
	 * defeated the gap limit and kept the scan running forever. The scan now
	 * stops at the chain cap.
	 */
	@Test(timeout = 600_000)
	public void historyForEveryAddressEndsAtTheChainCap() throws IOException {
		final int[] calls = {0};
		ElectrumRpc endless = new ElectrumRpc() {
			@Override
			public int blockHeight() {
				return 800000;
			}

			@Override
			public List<ElectrumClient.HistItem> getHistory(String sh) {
				calls[0]++;
				return java.util.Collections.singletonList(
						new ElectrumClient.HistItem(TXID, 1));
			}

			@Override
			public List<ElectrumClient.Utxo> listUnspent(String sh) {
				return new ArrayList<>();
			}

			@Override
			public String getTransaction(String txid) throws IOException {
				throw new ElectrumClient.ServerRejectedException("none");
			}

			@Override
			public String broadcast(String rawHex) throws IOException {
				throw new IOException("no");
			}

			@Override
			public double estimateFeeBtcPerKb(int blocks) {
				return 0.0001;
			}

			@Override
			public void close() {
			}
		};
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "w",
				(ep, port, tag) -> endless, (url, tag) -> null);
		w.scan();
		assertTrue("both chains stop at the cap: " + calls[0],
				calls[0] <= 2 * BtcWallet.MAX_CHAIN_INDEX);
	}

	@Test
	public void transactionCacheIsBounded() throws IOException {
		FakeElectrum e = new FakeElectrum();
		String raw = "010000000100000000000000000000000000000000000000000000"
				+ "00000000000000000000ffffffff4d04ffff001d0104455468652054696d"
				+ "65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e2062"
				+ "72696e6b206f66207365636f6e64206261696c6f757420666f722062616e"
				+ "6b73ffffffff0100f2052a01000000434104678afdb0fe5548271967f1a6"
				+ "7130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f355"
				+ "04e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000";
		int many = BtcWallet.MAX_TX_CACHE + 100;
		String sh = BtcKeys.scriptHash(MNEMONIC, 0, 0);
		for (int i = 0; i < many; i++) {
			String id = String.format("%064x", i + 1);
			e.addHistoryOnly(sh, id);
			e.txs.put(id, raw);
		}
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult r = w.scan();
		w.history(r);
		assertTrue("cache bounded: " + w.cachedTxCount(),
				w.cachedTxCount() <= BtcWallet.MAX_TX_CACHE);
	}

	@Test
	public void historyFetchesTheNewestTransactionsOnly() {
		java.util.LinkedHashMap<String, Integer> h = new java.util.LinkedHashMap<>();
		h.put("old", 10);
		h.put("unconfirmed", 0);
		h.put("newer", 50);
		h.put("newest", 90);
		java.util.LinkedHashMap<String, Integer> kept =
				BtcWallet.newestFirst(h, 2);
		assertEquals(2, kept.size());
		assertTrue(kept.containsKey("unconfirmed"));
		assertTrue(kept.containsKey("newest"));
	}

	@Test
	public void sumsBalanceAcrossUsedAddressesWithinGap() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TXID, 0, 10000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 5), TXID, 1, 20000);
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(30000L, r.balanceSat);
	}

	@Test
	public void utxoBeyondGapLimitIsNotDiscovered() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TXID, 0, 10000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 40), TXID, 0, 999999);
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(10000L, r.balanceSat);
	}

	@Test
	public void freshReceiveAddressIsFirstUnused() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addHistoryOnly(BtcKeys.scriptHash(MNEMONIC, 0, 0), TXID);
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(BtcKeys.address(MNEMONIC, 0, 1), r.receiveAddress);
	}

	@Test
	public void emptyWalletHasZeroBalanceAndFirstAddress() throws IOException {
		FakeElectrum e = new FakeElectrum();
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(0L, r.balanceSat);
		assertEquals(BtcKeys.address(MNEMONIC, 0, 0), r.receiveAddress);
	}

	@Test
	public void changeChainIsScannedForBalance() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.changeScriptHash(MNEMONIC, 0, 0), TXID, 0, 12345);
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(12345L, r.balanceSat);
	}
}
