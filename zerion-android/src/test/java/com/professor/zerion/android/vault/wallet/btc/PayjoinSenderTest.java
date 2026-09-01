package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinValidator;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyAnalyzer;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyEngine;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta;
import com.professor.zerion.android.vault.wallet.btc.privacy.UtxoOrigin;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PayjoinSenderTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String TX1 =
			"3333333333333333333333333333333333333333333333333333333333333333";
	private static final String FOREIGN_TX =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final long SEQ = 0xfffffffdL;

	private static BtcWallet wallet(FakeElectrum e) {
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPrivacyStore(new BtcWalletPrivacyTest.MemStore());
		return w;
	}

	private static byte[][] foreignWitness() {
		return new byte[][]{new byte[]{0x30, 0x44, 0x02}, new byte[]{0x02, 0x03}};
	}

	private static PayjoinValidator.Policy policy() {
		return new PayjoinValidator.Policy(50000, 0.5, 1000.0, 2000, false);
	}

	private static PayjoinValidator.Result okResult(BtcWallet.ScanResult scan,
			String ourOp, String changeAddr) {
		PayjoinValidator.OriginalTx orig = new PayjoinValidator.OriginalTx(
				Arrays.asList(ourOp), DEST, 40000, changeAddr, 55000);
		PayjoinValidator.ProposedTx prop = new PayjoinValidator.ProposedTx(
				Arrays.asList(ourOp, FOREIGN_TX + ":0"),
				new ArrayList<>(Arrays.asList(
						new PayjoinValidator.TxOut(DEST, 60000, "p2wpkh", true),
						new PayjoinValidator.TxOut(changeAddr, 54000, "p2wpkh",
								true))),
				120000, 200, 2, 0);
		return PayjoinValidator.validate(orig, prop, policy());
	}

	private static List<PayjoinValidator.TxOut> outs(String changeAddr) {
		return new ArrayList<>(Arrays.asList(
				new PayjoinValidator.TxOut(DEST, 60000, "p2wpkh", true),
				new PayjoinValidator.TxOut(changeAddr, 54000, "p2wpkh", true)));
	}

	private static PrivacyMeta meta(String outpoint, String address,
			UtxoOrigin origin, String cluster, boolean frozen) {
		return new PrivacyMeta(outpoint, 100000, address, origin, cluster, frozen,
				null);
	}

	private static PayjoinSender.ProposalInput ownedIn(BtcWallet.OwnedUtxo u,
			boolean claimsOurs) {
		return new PayjoinSender.ProposalInput(u.txHash, u.txPos, u.value, SEQ,
				null, claimsOurs);
	}

	private static PayjoinSender.ProposalInput foreignIn(boolean claimsOurs) {
		return new PayjoinSender.ProposalInput(FOREIGN_TX, 0, 20000, SEQ,
				foreignWitness(), claimsOurs);
	}

	private static BtcWallet.OwnedUtxo firstUtxo(BtcWallet.ScanResult scan) {
		return scan.utxos.get(0);
	}

	private PayjoinSender.Review assembleStandard(FakeElectrum e,
			List<PayjoinSender.ProposalInput> inputs,
			PayjoinValidator.Result nativeR, PayjoinValidator.Result javaR,
			List<PrivacyMeta> metas, PrivacyEngine.Policy pol,
			Set<String> authorized) throws IOException {
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		String changeAddr = scan.changeAddress;
		return PayjoinSender.assemble(scan, authorized, inputs, outs(changeAddr),
				2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000, metas, pol,
				nativeR, javaR);
	}

	@Test
	public void ownershipFromWalletStateSignsOnlyOwned() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u = firstUtxo(scan);
		String ourOp = u.txHash + ":" + u.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result r = okResult(scan, ourOp, changeAddr);
		Set<String> authorized = new HashSet<>(Arrays.asList(ourOp));
		List<PrivacyMeta> metas = Arrays.asList(
				meta(ourOp, u.address, u.origin, "addr:" + u.address, false));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u, true), foreignIn(false)),
				outs(changeAddr), 2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000,
				metas, PrivacyEngine.Policy.STANDARD, r, r);

		assertTrue(rev.ok);
		assertEquals(PayjoinSender.Reject.OK, rev.reject);
		assertEquals(new HashSet<>(Arrays.asList(ourOp)), rev.ownedOutpoints);
		assertEquals(new HashSet<>(Arrays.asList(FOREIGN_TX + ":0")),
				rev.foreignOutpoints);
		assertNotNull(rev.finalTx);
		assertEquals(new HashSet<>(Arrays.asList(ourOp)),
				rev.finalTx.ownedOutpoints());

		String hex = rev.finalTx.buildSignedHex();
		Transaction tx = new Transaction(BtcKeys.PARAMS, Utils.HEX.decode(hex));
		assertEquals(2, tx.getInputs().size());
		assertTrue(tx.getInput(0).getWitness().getPushCount() >= 2);
		byte[][] fw = foreignWitness();
		assertEquals(fw.length, tx.getInput(1).getWitness().getPushCount());
		assertTrue(Arrays.equals(fw[0], tx.getInput(1).getWitness().getPush(0)));
	}

	@Test
	public void spoofedOwnershipClaimIsNotSigned() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u = firstUtxo(scan);
		String ourOp = u.txHash + ":" + u.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result r = okResult(scan, ourOp, changeAddr);
		Set<String> authorized = new HashSet<>(Arrays.asList(ourOp));
		List<PrivacyMeta> metas = Arrays.asList(
				meta(ourOp, u.address, u.origin, "addr:" + u.address, false));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u, true), foreignIn(true)), outs(changeAddr),
				2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000, metas,
				PrivacyEngine.Policy.STANDARD, r, r);

		assertTrue(rev.ok);
		assertFalse(rev.ownedOutpoints.contains(FOREIGN_TX + ":0"));
		assertTrue(rev.foreignOutpoints.contains(FOREIGN_TX + ":0"));
		assertFalse(rev.finalTx.ownedOutpoints().contains(FOREIGN_TX + ":0"));
	}

	@Test
	public void extraOwnedInputRejected() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u0 = scan.utxos.get(0);
		BtcWallet.OwnedUtxo u1 = scan.utxos.get(1);
		String op0 = u0.txHash + ":" + u0.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result r = okResult(scan, op0, changeAddr);
		Set<String> authorized = new HashSet<>(Arrays.asList(op0));
		List<PrivacyMeta> metas = Arrays.asList(
				meta(op0, u0.address, u0.origin, "addr:" + u0.address, false),
				meta(u1.txHash + ":" + u1.txPos, u1.address, u1.origin,
						"addr:" + u1.address, false));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u0, true), ownedIn(u1, true)),
				outs(changeAddr), 2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000,
				metas, PrivacyEngine.Policy.STANDARD, r, r);

		assertFalse(rev.ok);
		assertEquals(PayjoinSender.Reject.OWNED_SET_CHANGED, rev.reject);
		assertNull(rev.finalTx);
	}

	@Test
	public void droppedOurInputRejected() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u = firstUtxo(scan);
		String ourOp = u.txHash + ":" + u.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result r = okResult(scan, ourOp, changeAddr);
		Set<String> authorized = new HashSet<>(Arrays.asList(ourOp));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(foreignIn(false)), outs(changeAddr), 2, 0, 6000,
				1.0, DEST, 60000, changeAddr, 54000, new ArrayList<>(),
				PrivacyEngine.Policy.STANDARD, r, r);

		assertFalse(rev.ok);
		assertEquals(PayjoinSender.Reject.OUR_INPUT_MISSING, rev.reject);
	}

	@Test
	public void validatorsDisagreeFailsClosed() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u = firstUtxo(scan);
		String ourOp = u.txHash + ":" + u.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result good = okResult(scan, ourOp, changeAddr);
		PayjoinValidator.OriginalTx orig = new PayjoinValidator.OriginalTx(
				Arrays.asList(ourOp), DEST, 40000, changeAddr, 55000);
		PayjoinValidator.ProposedTx bad = new PayjoinValidator.ProposedTx(
				Arrays.asList(FOREIGN_TX + ":0"),
				new ArrayList<>(Arrays.asList(
						new PayjoinValidator.TxOut(DEST, 60000, "p2wpkh", true))),
				60000, 200, 2, 0);
		PayjoinValidator.Result badR = PayjoinValidator.validate(orig, bad,
				policy());
		Set<String> authorized = new HashSet<>(Arrays.asList(ourOp));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u, true), foreignIn(false)),
				outs(changeAddr), 2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000,
				new ArrayList<>(), PrivacyEngine.Policy.STANDARD, good, badR);

		assertFalse(rev.ok);
		assertEquals(PayjoinSender.Reject.VALIDATORS_DISAGREE, rev.reject);
		assertNull(rev.finalTx);
	}

	@Test
	public void frozenOwnedInputRejected() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u = firstUtxo(scan);
		String ourOp = u.txHash + ":" + u.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result r = okResult(scan, ourOp, changeAddr);
		Set<String> authorized = new HashSet<>(Arrays.asList(ourOp));
		List<PrivacyMeta> metas = Arrays.asList(
				meta(ourOp, u.address, u.origin, "addr:" + u.address, true));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u, true), foreignIn(false)),
				outs(changeAddr), 2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000,
				metas, PrivacyEngine.Policy.STANDARD, r, r);

		assertFalse(rev.ok);
		assertEquals(PayjoinSender.Reject.FROZEN_INPUT, rev.reject);
	}

	@Test
	public void silentPaymentInputRejected() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u = firstUtxo(scan);
		String ourOp = u.txHash + ":" + u.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result r = okResult(scan, ourOp, changeAddr);
		Set<String> authorized = new HashSet<>(Arrays.asList(ourOp));
		List<PrivacyMeta> metas = Arrays.asList(meta(ourOp, u.address,
				UtxoOrigin.SILENT_PAYMENT, "sp:" + ourOp, false));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u, true), foreignIn(false)),
				outs(changeAddr), 2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000,
				metas, PrivacyEngine.Policy.STANDARD, r, r);

		assertFalse(rev.ok);
		assertEquals(PayjoinSender.Reject.SP_ISOLATION, rev.reject);
	}

	@Test
	public void strictClusterMergeRejected() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u0 = scan.utxos.get(0);
		BtcWallet.OwnedUtxo u1 = scan.utxos.get(1);
		String op0 = u0.txHash + ":" + u0.txPos;
		String op1 = u1.txHash + ":" + u1.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result r = okResult(scan, op0, changeAddr);
		Set<String> authorized = new HashSet<>(Arrays.asList(op0, op1));
		List<PrivacyMeta> metas = Arrays.asList(
				meta(op0, u0.address, u0.origin, "clusterA", false),
				meta(op1, u1.address, u1.origin, "clusterB", false));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u0, true), ownedIn(u1, true)),
				outs(changeAddr), 2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000,
				metas, PrivacyEngine.Policy.STRICT, r, r);

		assertFalse(rev.ok);
		assertEquals(PayjoinSender.Reject.CLUSTER_MERGE_STRICT, rev.reject);
	}

	@Test
	public void changeNotOursRejected() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u = firstUtxo(scan);
		String ourOp = u.txHash + ":" + u.txPos;
		PayjoinValidator.Result r = okResult(scan, ourOp, scan.changeAddress);
		Set<String> authorized = new HashSet<>(Arrays.asList(ourOp));
		List<PrivacyMeta> metas = Arrays.asList(
				meta(ourOp, u.address, u.origin, "addr:" + u.address, false));

		String external = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u, true), foreignIn(false)),
				outs(external), 2, 0, 6000, 1.0, DEST, 60000, external, 54000,
				metas, PrivacyEngine.Policy.STANDARD, r, r);

		assertFalse(rev.ok);
		assertEquals(PayjoinSender.Reject.CHANGE_NOT_OURS, rev.reject);
	}

	@Test
	public void p3AnalyzesFinalTxNotAutoHigh() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		BtcWallet.ScanResult scan = w.scan();
		BtcWallet.OwnedUtxo u = firstUtxo(scan);
		String ourOp = u.txHash + ":" + u.txPos;
		String changeAddr = scan.changeAddress;
		PayjoinValidator.Result r = okResult(scan, ourOp, changeAddr);
		Set<String> authorized = new HashSet<>(Arrays.asList(ourOp));
		List<PrivacyMeta> metas = Arrays.asList(
				meta(ourOp, u.address, u.origin, "addr:" + u.address, false));

		PayjoinSender.Review rev = PayjoinSender.assemble(scan, authorized,
				Arrays.asList(ownedIn(u, true), foreignIn(false)),
				outs(changeAddr), 2, 0, 6000, 1.0, DEST, 60000, changeAddr, 54000,
				metas, PrivacyEngine.Policy.STANDARD, r, r);

		assertTrue(rev.ok);
		assertNotEquals(PrivacyAnalyzer.Level.HIGH, rev.analysis.level);
		boolean common = false;
		for (PrivacyAnalyzer.Finding f : rev.analysis.findings) {
			if (f.code.equals(PrivacyAnalyzer.COMMON_INPUT)) {
				common = true;
			}
		}
		assertTrue(common);
	}
}
