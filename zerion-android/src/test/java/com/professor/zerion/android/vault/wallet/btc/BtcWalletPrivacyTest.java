package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyEngine;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyStore;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class BtcWalletPrivacyTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String TX1 =
			"3333333333333333333333333333333333333333333333333333333333333333";
	private static final String TX2 =
			"4444444444444444444444444444444444444444444444444444444444444444";

	static final class MemStore implements PrivacyStore {
		final Set<String> frozen = new HashSet<>();
		final Map<String, String> labels = new HashMap<>();
		final Map<String, String> hints = new HashMap<>();
		public Set<String> frozen() { return frozen; }
		public Map<String, String> labels() { return labels; }
		public Map<String, String> originHints() { return hints; }
		public void setFrozen(String o, boolean f) {
			if (f) frozen.add(o); else frozen.remove(o);
		}
		public void setLabel(String o, @Nullable String l) {
			if (l == null) labels.remove(o); else labels.put(o, l);
		}
		public void putOriginHint(String a, String c) { hints.put(a, c); }
	}

	private static BtcWallet wallet(FakeElectrum e, PrivacyStore store) {
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPrivacyStore(store);
		return w;
	}

	private static Set<String> inputOutpoints(FakeElectrum e) {
		Transaction tx = new Transaction(BtcKeys.PARAMS,
				Utils.HEX.decode(e.broadcasts.get(0)));
		Set<String> ops = new HashSet<>();
		for (int i = 0; i < tx.getInputs().size(); i++) {
			ops.add(tx.getInput(i).getOutpoint().getHash().toString());
		}
		return ops;
	}

	@Test
	public void frozenUtxoIsNotSpent() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 50000);
		MemStore store = new MemStore();
		store.setFrozen(TX0 + ":0", true);
		wallet(e, store).send(DEST, 40000, 1.0, false);
		Set<String> used = inputOutpoints(e);
		assertFalse(used.contains(TX0));
		assertTrue(used.contains(TX1));
	}

	@Test
	public void frozenUtxoExcludedFromSweep() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 50000);
		MemStore store = new MemStore();
		store.setFrozen(TX0 + ":0", true);
		wallet(e, store).send(DEST, 0, 1.0, true);
		Set<String> used = inputOutpoints(e);
		assertFalse(used.contains(TX0));
		assertTrue(used.contains(TX1));
	}

	@Test
	public void coinControlSpendsExactlyTheSelectedCoin() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 60000);
		Set<String> manual = new HashSet<>();
		manual.add(TX1 + ":0");
		wallet(e, new MemStore()).send(DEST, 40000, 1.0, false, manual);
		Set<String> used = inputOutpoints(e);
		assertTrue(used.contains(TX1));
		assertFalse(used.contains(TX0));
	}

	@Test
	public void coinControlUnavailableSelectionFailsClosed() {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		Set<String> manual = new HashSet<>();
		manual.add("ffff:0");
		assertThrows(IOException.class, () ->
				wallet(e, new MemStore()).send(DEST, 40000, 1.0, false, manual));
	}

	@Test
	public void fundsSpendableWithoutAnyPrivacyMetadata() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		String txid = w.send(DEST, 40000, 1.0, false);
		assertEquals(e.lastBroadcastTxid(), txid);
	}

	@Test
	public void strictPolicyPrefersTightestSingleCluster() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 60000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 40000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 2), TX2, 0, 300000);
		BtcWallet w = wallet(e, new MemStore());
		w.setPrivacyPolicy(PrivacyEngine.Policy.STRICT);
		w.send(DEST, 50000, 1.0, false);
		Set<String> used = inputOutpoints(e);
		assertTrue(used.contains(TX0));
		assertFalse(used.contains(TX2));
	}

	@Test
	public void coinControlListReturnsClassifiedMetadata() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 60000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 40000);
		List<PrivacyMeta> coins = wallet(e, new MemStore()).coinControl();
		assertEquals(2, coins.size());
		assertFalse(coins.get(0).clusterId.equals(coins.get(1).clusterId));
	}
}
