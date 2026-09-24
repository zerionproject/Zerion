package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Coin selection over random wallets: every input the built transaction
 * spends is one of the wallet's coins and none is spent twice, the inputs
 * cover the amount plus the fee, the fee is the fee rate times the size
 * estimate (plus a dropped dust change at most), the recipient output is
 * present, any change is above dust, outputs are sorted by value, and an
 * amount the wallet cannot cover is refused rather than partially paid.
 */
public class BtcCoinSelectionPropertyTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final long DUST = 294L;
	private static final int ROUNDS = 120;

	private final Random random = new Random(53);

	@Test
	public void builtTransactionsSpendOnlyWalletCoinsAndPayExactlyOnce()
			throws IOException {
		int refused = 0;
		for (int round = 0; round < ROUNDS; round++) {
			FakeElectrum e = new FakeElectrum();
			Map<String, Long> coins = new HashMap<>();
			int n = 1 + random.nextInt(6);
			long total = 0;
			for (int i = 0; i < n; i++) {
				long value = 1_000L + random.nextInt(2_000_000);
				String txid = randomTxid();
				int vout = random.nextInt(3);
				e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, i), txid, vout, value);
				coins.put(txid + ":" + vout, value);
				total += value;
			}
			long amount = DUST + 1 + (long) (random.nextDouble() * total * 0.7);
			double feeRate = 1 + random.nextInt(50);
			BtcWallet wallet = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999,
					"host", 50001, "walletA", new FakeElectrum.RecordingFactory(e),
					(url, tag) -> null);
			Transaction tx;
			try {
				wallet.send(DEST, amount, feeRate, false);
				tx = new Transaction(BtcKeys.PARAMS,
						Utils.HEX.decode(e.broadcasts.get(0)));
			} catch (IOException insufficient) {
				refused++;
				assertTrue("round " + round, e.broadcasts.isEmpty());
				continue;
			}

			long inSum = 0;
			java.util.Set<String> spent = new java.util.HashSet<>();
			for (TransactionInput in : tx.getInputs()) {
				String key = in.getOutpoint().getHash() + ":"
						+ in.getOutpoint().getIndex();
				assertTrue("round " + round + " spends unknown coin " + key,
						coins.containsKey(key));
				assertTrue("round " + round + " spends twice " + key,
						spent.add(key));
				inSum += coins.get(key);
			}
			long outSum = 0;
			long prev = -1;
			boolean recipient = false;
			for (TransactionOutput o : tx.getOutputs()) {
				long v = o.getValue().value;
				outSum += v;
				assertTrue("round " + round + " outputs unsorted", v >= prev);
				prev = v;
				if (v == amount && DEST.equals(o.getScriptPubKey()
						.getToAddress(BtcKeys.PARAMS).toString())) {
					recipient = true;
				} else {
					assertTrue("round " + round + " dust change " + v, v > DUST);
				}
			}
			assertTrue("round " + round + " recipient output missing", recipient);
			assertTrue(tx.getOutputs().size() <= 2);
			long fee = inSum - outSum;
			long expected = (long) Math.ceil(feeRate * BtcTx.estimateVBytes(
					tx.getInputs().size(), tx.getOutputs().size()));
			long expectedWithChange = (long) Math.ceil(feeRate
					* BtcTx.estimateVBytes(tx.getInputs().size(), 2));
			assertTrue("round " + round + " fee " + fee + " below " + expected,
					fee >= expected);
			assertTrue("round " + round + " fee " + fee + " above bound",
					fee <= expectedWithChange + DUST + 1);
			assertTrue(inSum >= amount + expected);
		}
		assertTrue("every round was refused", refused < ROUNDS);
	}

	private String randomTxid() {
		StringBuilder sb = new StringBuilder(64);
		for (int i = 0; i < 64; i++) {
			sb.append(Character.forDigit(random.nextInt(16), 16));
		}
		return sb.toString();
	}
}
