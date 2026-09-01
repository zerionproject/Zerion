package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;

public class FrozenCoinManualTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	@Test
	public void manualSelectionOfFrozenCoinIsRejectedAtWalletLayer()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWalletPrivacyTest.MemStore store =
				new BtcWalletPrivacyTest.MemStore();
		store.setFrozen(TX0 + ":0", true);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPrivacyStore(store);
		assertThrows(IOException.class, () -> w.planSend(DEST, 40000, 1.0, false,
				Collections.singleton(TX0 + ":0"), false));
	}
}
