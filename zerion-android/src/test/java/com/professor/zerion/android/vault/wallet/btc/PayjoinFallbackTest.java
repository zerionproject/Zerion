package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class PayjoinFallbackTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static BtcWallet wallet(FakeElectrum e) {
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPrivacyStore(new BtcWalletPrivacyTest.MemStore());
		return w;
	}

	@Test
	public void oldPayjoinAuthCannotSendNormalFallbackPlan() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.SendPlan normal =
				w.planSend(DEST, 40000, 1.0, false, null, false);
		SendGate gate = new SendGate();
		gate.prepare(normal);
		assertThrows(SendGate.AuthorizationException.class,
				() -> gate.authorize("payjoin-final-fingerprint", true));
		assertNull(gate.pending());
	}

	@Test
	public void explicitFallbackRebuildRequiresFreshAuth() throws Exception {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		SendGate gate = new SendGate();
		BtcWallet.SendPlan fresh =
				w.planSend(DEST, 40000, 1.0, false, null, false);
		gate.prepare(fresh);
		assertThrows(SendGate.AuthorizationException.class,
				() -> gate.authorize(fresh.fingerprint, false));
		BtcWallet.SendPlan authed = gate.authorize(fresh.fingerprint, true);
		assertSame(fresh, authed);
	}

	@Test
	public void reviewDoesNotTouchDurableBroadcastState() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		w.scan();
		assertTrue(w.pendingSummaries().isEmpty());
	}
}
