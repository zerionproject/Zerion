package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class BtcTxFingerprintTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
	private static final String TX_A =
			"0e53ec5dfb2cb8a71fec32dc9a634a35b7e24799295ddd5278217822e0b31f57";
	private static final String TX_B =
			"26aa6e6d8b9e49bb0630aac301db6757c02e3619feb4ee0eea81eb1672947024";

	private static Transaction build() {
		ECKey k = BtcKeys.receiveKey(MNEMONIC, 0, 0);
		String change = BtcKeys.changeAddress(MNEMONIC, 0, 0);
		List<BtcTx.Input> ins = Arrays.asList(
				new BtcTx.Input(TX_B, 1, 100000, k),
				new BtcTx.Input(TX_A, 0, 100000, k));
		List<BtcTx.Output> outs = Arrays.asList(
				new BtcTx.Output(DEST, 60000),
				new BtcTx.Output(change, 40000));
		String hex = BtcTx.buildAndSign(ins, outs);
		return new Transaction(BtcKeys.PARAMS, Utils.HEX.decode(hex));
	}

	@Test
	public void transactionIsVersionTwo() {
		assertEquals(2, build().getVersion());
	}

	@Test
	public void inputsAreBip69Sorted() {
		Transaction tx = build();
		assertEquals(TX_A,
				tx.getInput(0).getOutpoint().getHash().toString());
		assertEquals(TX_B,
				tx.getInput(1).getOutpoint().getHash().toString());
	}

	@Test
	public void outputsAreBip69SortedByValue() {
		Transaction tx = build();
		assertEquals(40000L, tx.getOutput(0).getValue().value);
		assertEquals(60000L, tx.getOutput(1).getValue().value);
	}

	@Test
	public void inputsCarryOptInRbfSequence() {
		Transaction tx = build();
		for (org.bitcoinj.core.TransactionInput in : tx.getInputs()) {
			assertTrue(in.getSequenceNumber() == 0xfffffffdL);
		}
	}
}
