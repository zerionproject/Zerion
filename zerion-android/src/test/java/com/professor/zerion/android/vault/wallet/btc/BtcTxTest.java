package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertTrue;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class BtcTxTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";

	@Test
	public void signsSpendableP2WPKH() {
		ECKey key = BtcKeys.receiveKey(MNEMONIC, 0, 0);
		long value = 100_000L;
		Script scriptPubKey = ScriptBuilder.createP2WPKHOutputScript(key);
		String prevTxid =
				"0000000000000000000000000000000000000000000000000000000000000001";

		List<BtcTx.Input> inputs = Collections.singletonList(
				new BtcTx.Input(prevTxid, 0, value, key));
		List<BtcTx.Output> outputs = Collections.singletonList(
				new BtcTx.Output(BtcKeys.address(MNEMONIC, 0, 1), 90_000L));

		String rawHex = BtcTx.buildAndSign(inputs, outputs);
		Transaction tx = new Transaction(BtcKeys.PARAMS, Utils.HEX.decode(rawHex));

		boolean valid;
		try {
			tx.getInput(0).getScriptSig().correctlySpends(tx, 0,
					tx.getInput(0).getWitness(), Coin.valueOf(value),
					scriptPubKey, Script.ALL_VERIFY_FLAGS);
			valid = true;
		} catch (Throwable e) {
			valid = false;
		}
		assertTrue(valid);
	}
}
