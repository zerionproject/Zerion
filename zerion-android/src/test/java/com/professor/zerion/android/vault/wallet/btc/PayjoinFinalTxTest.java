package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PayjoinFinalTxTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String FOREIGN_TX =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final long SEQ = 0xfffffffdL;

	private static byte[][] foreignWitness() {
		return new byte[][]{new byte[]{0x30, 0x44, 0x01}, new byte[]{0x02, 0x21}};
	}

	private static ECKey ownedKey() {
		return BtcKeys.receiveKey(MNEMONIC, 0, 0);
	}

	private static PayjoinFinalTx tx(long changeSat) {
		String changeAddr = BtcKeys.changeAddress(MNEMONIC, 0, 0);
		List<PayjoinFinalTx.Entry> entries = new ArrayList<>(Arrays.asList(
				PayjoinFinalTx.Entry.owned(TX0, 0, 100000, SEQ, ownedKey()),
				PayjoinFinalTx.Entry.foreign(FOREIGN_TX, 0, 20000, SEQ,
						foreignWitness())));
		List<BtcTx.Output> outputs = new ArrayList<>(Arrays.asList(
				new BtcTx.Output(DEST, 60000),
				new BtcTx.Output(changeAddr, changeSat)));
		return new PayjoinFinalTx(entries, outputs, 2, 0, DEST, 60000,
				changeAddr, changeSat, 6000, 1.0);
	}

	@Test
	public void fingerprintStableForSameTx() {
		assertEquals(tx(54000).fingerprint(), tx(54000).fingerprint());
	}

	@Test
	public void fingerprintChangesOnOutputMutation() {
		assertNotEquals(tx(54000).fingerprint(), tx(53000).fingerprint());
	}

	@Test
	public void ownedOutpointsAreOnlyWalletInputs() {
		assertEquals(new java.util.HashSet<>(Arrays.asList(TX0 + ":0")),
				tx(54000).ownedOutpoints());
	}

	@Test
	public void buildSignedHexSignsOnlyOwnedInputs() {
		PayjoinFinalTx t = tx(54000);
		String hex = t.buildSignedHex();
		Transaction parsed =
				new Transaction(BtcKeys.PARAMS, Utils.HEX.decode(hex));
		assertEquals(2, parsed.getInputs().size());
		assertTrue(parsed.getInput(0).getWitness().getPushCount() >= 2);
		byte[][] fw = foreignWitness();
		assertEquals(fw.length, parsed.getInput(1).getWitness().getPushCount());
		assertTrue(Arrays.equals(fw[0],
				parsed.getInput(1).getWitness().getPush(0)));
		assertTrue(Arrays.equals(fw[1],
				parsed.getInput(1).getWitness().getPush(1)));
	}
}
