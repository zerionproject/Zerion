package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A2-REG-BTC-08: a server answering the per-call maximum for every index
 * is cut off by an aggregate bound instead of filling the heap.
 */
public class ScanBoundsTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";

	private static final class Flood implements ElectrumRpc {
		int calls = 0;

		@Override
		public int blockHeight() {
			return 800000;
		}

		@Override
		public List<ElectrumClient.HistItem> getHistory(String scriptHash) {
			List<ElectrumClient.HistItem> out = new ArrayList<>();
			for (int i = 0; i < ElectrumClient.MAX_LIST_ITEMS; i++) {
				out.add(new ElectrumClient.HistItem(txid(calls, i), 1));
			}
			calls++;
			return out;
		}

		@Override
		public List<ElectrumClient.Utxo> listUnspent(String scriptHash) {
			List<ElectrumClient.Utxo> out = new ArrayList<>();
			for (int i = 0; i < ElectrumClient.MAX_LIST_ITEMS; i++) {
				out.add(new ElectrumClient.Utxo(txid(calls, i), 0, 1, 1000));
			}
			return out;
		}

		@Override
		public String getTransaction(String txid) throws IOException {
			throw new ElectrumClient.ServerRejectedException("no tx");
		}

		@Override
		public String broadcast(String rawHex) throws IOException {
			throw new IOException("not used");
		}

		@Override
		public double estimateFeeBtcPerKb(int blocks) {
			return 0.0002;
		}

		@Override
		public void close() {
		}

		private static String txid(int call, int i) {
			String s = Integer.toHexString(call) + Integer.toHexString(i);
			StringBuilder sb = new StringBuilder(64);
			while (sb.length() + s.length() < 64) sb.append('0');
			return sb.append(s).toString();
		}
	}

	@Test(timeout = 60_000)
	public void theScanStopsAtTheAggregateBound() {
		Flood e = new Flood();
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "host",
				50001, "walletA", (ep, port, tag) -> e,
				(url, tag) -> null);
		try {
			w.scan();
			fail();
		} catch (IOException expected) {
		}
		assertTrue(e.calls > 0);
		assertTrue("stopped long before the chain limit",
				e.calls < BtcWallet.MAX_SCAN_ITEMS / ElectrumClient.MAX_LIST_ITEMS + 2);
	}

	@Test
	public void aWalletWithHistoryIsNeverShownAsEmpty() {
		java.util.Set<String> had = new java.util.HashSet<>();
		had.add("a");
		java.util.Set<String> none = new java.util.HashSet<>();
		assertTrue(BtcWallet.emptiedAfterHistory(had, none));
		assertFalse("first scan has no baseline",
				BtcWallet.emptiedAfterHistory(null, none));
		assertFalse(BtcWallet.emptiedAfterHistory(none, none));
		assertFalse("history that changed is published",
				BtcWallet.emptiedAfterHistory(had, had));
	}
}
