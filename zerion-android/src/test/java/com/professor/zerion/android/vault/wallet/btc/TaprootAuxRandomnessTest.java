package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Key-path signatures use fresh auxiliary randomness: signing the same
 * transaction twice yields different, valid signatures, so a fault or a
 * side channel during one signature does not reveal the nonce of another.
 */
public class TaprootAuxRandomnessTest {

	@Test
	public void twoSignaturesOfTheSameTransactionDiffer() {
		BigInteger priv = new BigInteger(
				"1b7b0d8f7e7e6a6e1a5d2e9e8c2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d0c",
				16);
		byte[] x = ECKey.fromPrivate(priv, true).getPubKeyPoint().normalize()
				.getXCoord().getEncoded();
		byte[] spk = new byte[34];
		spk[0] = 0x51;
		spk[1] = 0x20;
		System.arraycopy(x, 0, spk, 2, 32);
		String txid =
				"1111111111111111111111111111111111111111111111111111111111111111";
		List<BtcTx.TaprootInput> inputs = Collections.singletonList(
				new BtcTx.TaprootInput(txid, 0, 100_000L, spk, priv));
		List<BtcTx.Output> outputs = Collections.singletonList(
				new BtcTx.Output("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
						90_000L));

		String first = BtcTx.buildAndSignTaproot(inputs, outputs);
		String second = BtcTx.buildAndSignTaproot(inputs, outputs);
		assertFalse(first.equals(second));

		Transaction a = new Transaction(BtcKeys.PARAMS, Utils.HEX.decode(first));
		Transaction b = new Transaction(BtcKeys.PARAMS, Utils.HEX.decode(second));
		assertEquals(a.getTxId(), b.getTxId());
		byte[] sigA = a.getInput(0).getWitness().getPush(0);
		byte[] sigB = b.getInput(0).getWitness().getPush(0);
		assertEquals(64, sigA.length);
		assertEquals(64, sigB.length);
		assertFalse(Arrays.equals(sigA, sigB));
		assertTrue(a.getInput(0).getWitness().getPushCount() == 1);
	}
}
