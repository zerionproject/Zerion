package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.IOException;

public class BtcCoinSelectionTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String TX1 =
			"3333333333333333333333333333333333333333333333333333333333333333";

	private static BtcWallet wallet(FakeElectrum e) {
		return new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
	}

	private static Transaction lastTx(FakeElectrum e) {
		return new Transaction(BtcKeys.PARAMS,
				Utils.HEX.decode(e.broadcasts.get(0)));
	}

	@Test
	public void selectsInputBuildsChangeAndBroadcasts() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		String txid = wallet(e).send(DEST, 50000, 1.0, false);
		assertEquals(e.lastBroadcastTxid(), txid);
		assertEquals(1, e.broadcasts.size());
		Transaction tx = lastTx(e);
		assertEquals(1, tx.getInputs().size());
		assertEquals(2, tx.getOutputs().size());
		assertEquals(2, tx.getVersion());
		boolean recipientPresent = false;
		long prev = -1;
		for (org.bitcoinj.core.TransactionOutput o : tx.getOutputs()) {
			if (o.getValue().value == 50000L) {
				recipientPresent = true;
			}
			assertTrue(o.getValue().value >= prev);
			prev = o.getValue().value;
		}
		assertTrue(recipientPresent);
	}

	@Test
	public void insufficientFundsIncludingFeeThrows() {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		assertThrows(IOException.class, () -> wallet(e).send(DEST, 100000, 1.0,
				false));
	}

	@Test
	public void dustChangeIsDroppedToFee() throws IOException {
		FakeElectrum e = new FakeElectrum();
		long amount = 50000;
		long fee = BtcTx.estimateVBytes(1, 2);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0,
				amount + fee + 100);
		wallet(e).send(DEST, amount, 1.0, false);
		Transaction tx = lastTx(e);
		assertEquals(1, tx.getOutputs().size());
		assertEquals(amount, tx.getOutput(0).getValue().value);
	}

	@Test
	public void sweepSpendsEveryUtxo() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 40000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 60000);
		wallet(e).send(DEST, 0, 1.0, true);
		Transaction tx = lastTx(e);
		assertEquals(2, tx.getInputs().size());
		assertEquals(1, tx.getOutputs().size());
		long fee = BtcTx.estimateVBytes(2, 1);
		assertEquals(100000L - fee, tx.getOutput(0).getValue().value);
	}

	@Test
	public void belowDustAmountThrows() {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		assertThrows(IOException.class, () -> wallet(e).send(DEST, 100, 1.0,
				false));
	}

	@Test
	public void noSpendableCoinsSweepThrows() {
		FakeElectrum e = new FakeElectrum();
		assertThrows(IOException.class, () -> wallet(e).send(DEST, 0, 1.0,
				true));
	}
}
