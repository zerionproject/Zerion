package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
		return TestKeys.receiveKey(MNEMONIC, 0, 0);
	}

	private static PayjoinFinalTx tx(long changeSat) {
		return tx(ownedKey(), 100000, 20000, foreignWitness(), changeSat, 6000);
	}

	private static PayjoinFinalTx tx(ECKey key, long ownedSat, long foreignSat,
			byte[][] witness, long changeSat, long feeSat) {
		String changeAddr = TestKeys.changeAddress(MNEMONIC, 0, 0);
		List<PayjoinFinalTx.Entry> entries = new ArrayList<>(Arrays.asList(
				PayjoinFinalTx.Entry.owned(TX0, 0, ownedSat, SEQ, key),
				PayjoinFinalTx.Entry.foreign(FOREIGN_TX, 0, foreignSat, SEQ,
						witness)));
		List<BtcTx.Output> outputs = new ArrayList<>(Arrays.asList(
				new BtcTx.Output(DEST, 60000),
				new BtcTx.Output(changeAddr, changeSat)));
		return new PayjoinFinalTx(entries, outputs, 2, 0, DEST, 60000,
				changeAddr, changeSat, feeSat, 1.0);
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
	public void fingerprintCoversTheAmountOfEveryInput() {
		String reviewed = tx(54000).fingerprint();
		assertNotEquals("our own input amount", reviewed,
				tx(ownedKey(), 100001, 20000, foreignWitness(), 54000, 6000)
						.fingerprint());
		assertNotEquals("the receiver's input amount", reviewed,
				tx(ownedKey(), 100000, 19999, foreignWitness(), 54000, 6000)
						.fingerprint());
	}

	@Test
	public void fingerprintCoversTheSigningScriptAndTheForeignWitness() {
		String reviewed = tx(54000).fingerprint();
		assertNotEquals(reviewed, tx(TestKeys.receiveKey(MNEMONIC, 0, 1),
				100000, 20000, foreignWitness(), 54000, 6000).fingerprint());
		byte[][] otherWitness = foreignWitness();
		otherWitness[1][1] = 0x22;
		assertNotEquals(reviewed, tx(ownedKey(), 100000, 20000, otherWitness,
				54000, 6000).fingerprint());
	}

	@Test
	public void onlyTheReviewedTransactionIsSigned() {
		PayjoinFinalTx reviewed = tx(54000);
		String fingerprint = reviewed.fingerprint();
		assertNotNull(reviewed.buildSignedHexApproved(fingerprint));
		assertNull("a different input amount under the reviewed fingerprint",
				tx(ownedKey(), 100001, 20000, foreignWitness(), 54001, 6000)
						.buildSignedHexApproved(fingerprint));
		assertNull("a different signing key under the reviewed fingerprint",
				tx(TestKeys.receiveKey(MNEMONIC, 0, 1), 100000, 20000,
						foreignWitness(), 54000, 6000)
						.buildSignedHexApproved(fingerprint));
		assertNull(reviewed.buildSignedHexApproved("deadbeef"));
	}

	@Test
	public void aFeeThatDoesNotMatchTheAmountsIsNeverSigned() {
		PayjoinFinalTx wrongFee = tx(ownedKey(), 100000, 20000,
				foreignWitness(), 54000, 5000);
		assertNull(wrongFee.buildSignedHexApproved(wrongFee.fingerprint()));
		PayjoinFinalTx hiddenInput = tx(ownedKey(), 150000, 20000,
				foreignWitness(), 54000, 6000);
		assertNull(hiddenInput.buildSignedHexApproved(
				hiddenInput.fingerprint()));
	}

	@Test
	public void changingWhatItWasBuiltFromChangesNothingThatIsSigned() {
		String changeAddr = TestKeys.changeAddress(MNEMONIC, 0, 0);
		byte[][] witness = foreignWitness();
		List<PayjoinFinalTx.Entry> entries = new ArrayList<>(Arrays.asList(
				PayjoinFinalTx.Entry.owned(TX0, 0, 100000, SEQ, ownedKey()),
				PayjoinFinalTx.Entry.foreign(FOREIGN_TX, 0, 20000, SEQ,
						witness)));
		List<BtcTx.Output> outputs = new ArrayList<>(Arrays.asList(
				new BtcTx.Output(DEST, 60000),
				new BtcTx.Output(changeAddr, 54000)));
		PayjoinFinalTx t = new PayjoinFinalTx(entries, outputs, 2, 0, DEST,
				60000, changeAddr, 54000, 6000, 1.0);
		String reviewed = t.fingerprint();
		entries.set(0, PayjoinFinalTx.Entry.owned(TX0, 0, 999999, SEQ,
				ownedKey()));
		outputs.add(new BtcTx.Output(changeAddr, 1000));
		witness[0][0] = 0x31;
		assertEquals(reviewed, t.fingerprint());
		assertNotNull(t.buildSignedHexApproved(reviewed));
	}

	@Test
	public void ownedOutpointsAreOnlyWalletInputs() {
		assertEquals(new java.util.HashSet<>(Arrays.asList(TX0 + ":0")),
				tx(54000).ownedOutpoints());
	}

	@Test
	public void buildSignedHexSignsOnlyOwnedInputs() {
		PayjoinFinalTx t = tx(54000);
		String hex = t.buildSignedHexApproved(t.fingerprint());
		assertNotNull(hex);
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
