package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyEngine;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMergeException;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class BtcWalletExtremePrivacyTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String TX1 =
			"3333333333333333333333333333333333333333333333333333333333333333";

	private static BtcWallet wallet(FakeElectrum e, PrivacyEngine.Policy policy) {
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPrivacyStore(new BtcWalletPrivacyTest.MemStore());
		w.setPrivacyPolicy(policy);
		return w;
	}

	private static FakeElectrum twoClusters(long a, long b) {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, a);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, b);
		return e;
	}

	private static int inputCount(FakeElectrum e) {
		return new Transaction(BtcKeys.PARAMS,
				Utils.HEX.decode(e.broadcasts.get(0))).getInputs().size();
	}

	@Test
	public void toggleOffStandardMergesWithoutWarning() throws IOException {
		FakeElectrum e = twoClusters(30000, 40000);
		String txid = wallet(e, PrivacyEngine.Policy.STANDARD)
				.send(DEST, 50000, 1.0, false);
		assertEquals(e.lastBroadcastTxid(), txid);
		assertEquals(2, inputCount(e));
	}

	@Test
	public void toggleOnSingleClusterSpendSucceedsNoWarning()
			throws IOException {
		FakeElectrum e = twoClusters(60000, 40000);
		String txid = wallet(e, PrivacyEngine.Policy.STRICT)
				.send(DEST, 50000, 1.0, false);
		assertEquals(e.lastBroadcastTxid(), txid);
		assertEquals(1, inputCount(e));
	}

	@Test
	public void toggleOnMultiClusterRequirementThrowsBeforeSigning() {
		FakeElectrum e = twoClusters(30000, 40000);
		PrivacyMergeException ex = assertThrows(PrivacyMergeException.class,
				() -> wallet(e, PrivacyEngine.Policy.STRICT)
						.send(DEST, 50000, 1.0, false));
		assertEquals(2, ex.clusterCount);
		assertTrue(e.broadcasts.isEmpty());
	}

	@Test
	public void explicitOverrideAllowsMerge() throws IOException {
		FakeElectrum e = twoClusters(30000, 40000);
		String txid = wallet(e, PrivacyEngine.Policy.STRICT)
				.send(DEST, 50000, 1.0, false, null, true);
		assertEquals(e.lastBroadcastTxid(), txid);
		assertEquals(2, inputCount(e));
	}

	@Test
	public void manualCoinControlAcrossClustersThrows() {
		FakeElectrum e = twoClusters(60000, 60000);
		Set<String> manual = new HashSet<>();
		manual.add(TX0 + ":0");
		manual.add(TX1 + ":0");
		assertThrows(PrivacyMergeException.class,
				() -> wallet(e, PrivacyEngine.Policy.STRICT)
						.send(DEST, 40000, 1.0, false, manual));
	}

	@Test
	public void manualCoinControlAuthoritativeWithOverride()
			throws IOException {
		FakeElectrum e = twoClusters(60000, 60000);
		Set<String> manual = new HashSet<>();
		manual.add(TX0 + ":0");
		manual.add(TX1 + ":0");
		wallet(e, PrivacyEngine.Policy.STRICT)
				.send(DEST, 40000, 1.0, false, manual, true);
		Transaction tx = new Transaction(BtcKeys.PARAMS,
				Utils.HEX.decode(e.broadcasts.get(0)));
		Set<String> used = new HashSet<>();
		for (int i = 0; i < tx.getInputs().size(); i++) {
			used.add(tx.getInput(i).getOutpoint().getHash().toString());
		}
		assertTrue(used.contains(TX0));
		assertTrue(used.contains(TX1));
	}

	@Test
	public void strictNeverSilentlyFallsBackToStandard() {
		FakeElectrum e = twoClusters(30000, 40000);
		assertThrows(PrivacyMergeException.class,
				() -> wallet(e, PrivacyEngine.Policy.STRICT)
						.send(DEST, 50000, 1.0, false));
	}

	@Test
	public void strictSpendableWithoutMetadataSingleCluster()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPrivacyPolicy(PrivacyEngine.Policy.STRICT);
		String txid = w.send(DEST, 40000, 1.0, false);
		assertEquals(e.lastBroadcastTxid(), txid);
	}
}
