package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SigningBindingAttackTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String OTHER =
			"bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g";
	private static final String TAPROOT_DEST =
			"bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String TX1 =
			"3333333333333333333333333333333333333333333333333333333333333333";

	private static BtcWallet wallet(FakeElectrum e) {
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "host",
				50001, "walletA", new FakeElectrum.RecordingFactory(e),
				(url, tag) -> null);
		w.setPendingLog(new BroadcastDurabilityTest.MemLog());
		return w;
	}

	private static FakeElectrum funded() {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		return e;
	}

	private static List<String> outpointsOf(List<BtcTx.Input> ins) {
		List<String> ops = new ArrayList<>();
		for (BtcTx.Input in : ins) ops.add(in.txHash + ":" + in.txPos);
		return ops;
	}

	private static BtcWallet.SendPlan plan(BtcWallet.SendPlan p, String to,
			long amount, long fee, long net, List<BtcTx.Input> ins,
			List<BtcTx.Output> outs, int changeIndex, String fingerprint) {
		return new BtcWallet.SendPlan(to, amount, fee, net, p.sweep,
				outpointsOf(ins), fingerprint, ins, outs, p.inputMetas,
				p.hasChange, p.changeCluster, p.reusedOutpoints, p.manual,
				p.vbytes, changeIndex, p.owner);
	}

	private static BtcTx.Output changeOf(BtcWallet.SendPlan p) {
		for (BtcTx.Output o : p.outputs) {
			if (!o.address.equals(p.toAddress)) return o;
		}
		throw new AssertionError("no change output");
	}

	private static void assertRefused(BtcWallet w, FakeElectrum e,
			BtcWallet.SendPlan p) {
		SendGate gate = new SendGate();
		gate.prepare(p, w.walletId());
		BtcWallet.SendPlan approved;
		try {
			approved = gate.authorize(p.fingerprint, true, w.walletId());
		} catch (SendGate.AuthorizationException ex) {
			throw new AssertionError(ex);
		}
		IOException refused = assertThrows(IOException.class,
				() -> w.signPlan(approved));
		assertFalse(refused instanceof BroadcastUncertainException);
		assertEquals(BtcWallet.ERR_PLAN_CHANGED, refused.getMessage());
		assertTrue("nothing may be broadcast", e.broadcasts.isEmpty());
	}

	@Test
	public void theFingerprintCoversEverythingTheSignatureCommitsTo() {
		ECKey k0 = TestKeys.receiveKey(MNEMONIC, 0, 0);
		ECKey k1 = TestKeys.receiveKey(MNEMONIC, 0, 1);
		List<BtcTx.Input> ins = Arrays.asList(
				new BtcTx.Input(TX0, 0, 100000, k0),
				new BtcTx.Input(TX1, 1, 50000, k1));
		List<BtcTx.Output> outs = Arrays.asList(
				new BtcTx.Output(DEST, 120000),
				new BtcTx.Output(OTHER, 29000));
		String base = BtcTx.fingerprint(ins, outs);

		List<List<BtcTx.Input>> inputVariants = Arrays.asList(
				Arrays.asList(new BtcTx.Input(TX0, 0, 100001, k0), ins.get(1)),
				Arrays.asList(new BtcTx.Input(TX0, 1, 100000, k0), ins.get(1)),
				Arrays.asList(new BtcTx.Input(TX1, 0, 100000, k0), ins.get(1)),
				Arrays.asList(new BtcTx.Input(TX0, 0, 100000, k1), ins.get(1)),
				Collections.singletonList(ins.get(0)));
		for (List<BtcTx.Input> v : inputVariants) {
			assertNotEquals(base, BtcTx.fingerprint(v, outs));
		}
		List<List<BtcTx.Output>> outputVariants = Arrays.asList(
				Arrays.asList(new BtcTx.Output(OTHER, 120000), outs.get(1)),
				Arrays.asList(new BtcTx.Output(DEST, 119999), outs.get(1)),
				Arrays.asList(outs.get(0), new BtcTx.Output(DEST, 29000)),
				Arrays.asList(outs.get(0), outs.get(1),
						new BtcTx.Output(OTHER, 1000)),
				Collections.singletonList(outs.get(0)));
		for (List<BtcTx.Output> v : outputVariants) {
			assertNotEquals(base, BtcTx.fingerprint(ins, v));
		}

		List<BtcTx.Input> reorderedIns = Arrays.asList(ins.get(1), ins.get(0));
		List<BtcTx.Output> reorderedOuts =
				Arrays.asList(outs.get(1), outs.get(0));
		assertEquals("list order does not change the signed transaction",
				base, BtcTx.fingerprint(reorderedIns, reorderedOuts));
		assertEquals(BtcTx.buildAndSign(ins, outs),
				BtcTx.buildAndSign(reorderedIns, reorderedOuts));
	}

	@Test
	public void theApprovedPathSignsExactlyTheSameBytesAndRefusesAnyOther() {
		ECKey k0 = TestKeys.receiveKey(MNEMONIC, 0, 0);
		List<BtcTx.Input> ins = Collections.singletonList(
				new BtcTx.Input(TX0, 0, 100000, k0));
		List<BtcTx.Output> outs = Arrays.asList(
				new BtcTx.Output(DEST, 60000), new BtcTx.Output(OTHER, 39000));
		String fp = BtcTx.fingerprint(ins, outs);
		String approved = BtcTx.buildAndSignApproved(ins, outs, fp);
		assertEquals(BtcTx.buildAndSign(ins, outs), approved);
		assertNull(BtcTx.buildAndSignApproved(ins, outs, fp.substring(1) + "0"));
		assertNull(BtcTx.buildAndSignApproved(ins,
				Arrays.asList(new BtcTx.Output(DEST, 60000),
						new BtcTx.Output(OTHER, 38000)), fp));

		Transaction tx = new Transaction(BtcKeys.PARAMS,
				Utils.HEX.decode(approved));
		assertEquals(2, tx.getVersion());
		assertEquals(0, tx.getLockTime());
		for (TransactionInput in : tx.getInputs()) {
			assertEquals(0xfffffffdL, in.getSequenceNumber());
		}
	}

	@Test
	public void theTaprootFingerprintCoversAmountsScriptsAndOutputs() {
		byte[] spk = new byte[34];
		spk[0] = 0x51;
		spk[1] = 0x20;
		spk[33] = 7;
		byte[] otherSpk = spk.clone();
		otherSpk[33] = 8;
		List<BtcTx.TaprootInput> ins = Collections.singletonList(
				new BtcTx.TaprootInput(TX0, 0, 100000, spk, BigInteger.TEN));
		List<BtcTx.Output> outs =
				Collections.singletonList(new BtcTx.Output(DEST, 99000));
		String base = BtcTx.taprootFingerprint(ins, outs);
		assertNotEquals(base, BtcTx.taprootFingerprint(Collections.singletonList(
				new BtcTx.TaprootInput(TX0, 0, 100001, spk, BigInteger.TEN)),
				outs));
		assertNotEquals(base, BtcTx.taprootFingerprint(Collections.singletonList(
				new BtcTx.TaprootInput(TX0, 0, 100000, otherSpk,
						BigInteger.TEN)), outs));
		assertNotEquals(base, BtcTx.taprootFingerprint(Collections.singletonList(
				new BtcTx.TaprootInput(TX1, 0, 100000, spk, BigInteger.TEN)),
				outs));
		assertNotEquals(base, BtcTx.taprootFingerprint(ins,
				Collections.singletonList(new BtcTx.Output(TAPROOT_DEST, 99000))));
		assertNotEquals(base, BtcTx.taprootFingerprint(ins,
				Collections.singletonList(new BtcTx.Output(DEST, 98999))));
		assertNotEquals("the two input types never share a fingerprint", base,
				BtcTx.fingerprint(Collections.singletonList(new BtcTx.Input(
						TX0, 0, 100000, TestKeys.receiveKey(MNEMONIC, 0, 0))),
						outs));
	}

	@Test
	public void everyMutationOfAReviewedPlanIsRefusedBeforeSigning()
			throws Exception {
		FakeElectrum e = funded();
		BtcWallet w = wallet(e);
		BtcWallet.SendPlan p = w.planSend(DEST, 40000, 2.0, false, null, false);
		assertTrue(p.hasChange);
		BtcTx.Output change = changeOf(p);
		BtcTx.Input in = p.inputs.get(0);
		BtcTx.Output pay = new BtcTx.Output(DEST, 40000);
		List<BtcWallet.SendPlan> attacks = new ArrayList<>();

		List<BtcTx.Input> moreValue = Collections.singletonList(
				new BtcTx.Input(in.txHash, in.txPos, in.valueSat + 5000, in.key));
		attacks.add(plan(p, DEST, 40000, p.feeSat + 5000, p.netSat - 5000,
				moreValue, p.outputs, p.changeIndex, p.fingerprint));

		List<BtcTx.Input> otherKey = Collections.singletonList(
				new BtcTx.Input(in.txHash, in.txPos, in.valueSat,
						TestKeys.receiveKey(MNEMONIC, 0, 5)));
		attacks.add(plan(p, DEST, 40000, p.feeSat, p.netSat, otherKey,
				p.outputs, p.changeIndex, p.fingerprint));

		List<BtcTx.Input> otherOutpoint = Collections.singletonList(
				new BtcTx.Input(TX1, in.txPos, in.valueSat, in.key));
		attacks.add(plan(p, DEST, 40000, p.feeSat, p.netSat, otherOutpoint,
				p.outputs, p.changeIndex, p.fingerprint));

		List<BtcTx.Output> stolenChange = Arrays.asList(pay,
				new BtcTx.Output(OTHER, change.valueSat));
		attacks.add(plan(p, DEST, 40000, p.feeSat, p.netSat, p.inputs,
				stolenChange, p.changeIndex,
				BtcTx.fingerprint(p.inputs, stolenChange)));

		List<BtcTx.Output> foreignChange = Arrays.asList(pay,
				new BtcTx.Output(TestKeys.changeAddress(MNEMONIC, 0,
						p.changeIndex + 3), change.valueSat));
		attacks.add(plan(p, DEST, 40000, p.feeSat, p.netSat, p.inputs,
				foreignChange, p.changeIndex,
				BtcTx.fingerprint(p.inputs, foreignChange)));

		List<BtcTx.Output> higherFee = Arrays.asList(pay,
				new BtcTx.Output(change.address, change.valueSat - 3000));
		attacks.add(plan(p, DEST, 40000, p.feeSat, p.netSat, p.inputs,
				higherFee, p.changeIndex,
				BtcTx.fingerprint(p.inputs, higherFee)));

		attacks.add(plan(p, OTHER, 40000, p.feeSat, p.netSat, p.inputs,
				p.outputs, p.changeIndex, p.fingerprint));
		attacks.add(plan(p, DEST, 4000, p.feeSat, p.netSat, p.inputs,
				p.outputs, p.changeIndex, p.fingerprint));
		attacks.add(plan(p, DEST, 40000, p.feeSat - 100, p.netSat, p.inputs,
				p.outputs, p.changeIndex, p.fingerprint));
		attacks.add(plan(p, DEST, 40000, p.feeSat, p.netSat + 1, p.inputs,
				p.outputs, p.changeIndex, p.fingerprint));
		attacks.add(plan(p, DEST, 40000, p.feeSat, p.netSat, p.inputs,
				p.outputs, p.changeIndex + 1, p.fingerprint));
		attacks.add(plan(p, DEST, 40000, p.feeSat, p.netSat, p.inputs,
				p.outputs, -1, p.fingerprint));

		List<BtcTx.Output> extra = new ArrayList<>(p.outputs);
		extra.add(new BtcTx.Output(OTHER, 1000));
		attacks.add(plan(p, DEST, 40000, p.feeSat - 1000, p.netSat, p.inputs,
				extra, p.changeIndex, BtcTx.fingerprint(p.inputs, extra)));

		for (BtcWallet.SendPlan attack : attacks) {
			assertRefused(w, e, attack);
		}

		BtcWallet.SendPlan reordered = plan(p, DEST, 40000, p.feeSat, p.netSat,
				p.inputs, Arrays.asList(p.outputs.get(1), p.outputs.get(0)),
				p.changeIndex, p.fingerprint);
		w.signPlan(reordered);
		assertEquals(1, e.broadcasts.size());
	}

	@Test
	public void aPlanFromAnotherWalletSessionIsNeverSigned() throws Exception {
		FakeElectrum e = funded();
		BtcWallet before = wallet(e);
		BtcWallet.SendPlan reviewed =
				before.planSend(DEST, 40000, 2.0, false, null, false);
		BtcWallet after = wallet(e);
		assertEquals("the same wallet reopened", before.walletId(),
				after.walletId());

		SendGate gate = new SendGate();
		gate.prepare(reviewed, before.walletId());
		BtcWallet.SendPlan approved =
				gate.authorize(reviewed.fingerprint, true, after.walletId());
		IOException refused = assertThrows(IOException.class,
				() -> after.signPlan(approved));
		assertEquals(BtcWallet.ERR_PLAN_CHANGED, refused.getMessage());

		before.close();
		assertThrows(IOException.class, () -> before.signPlan(reviewed));

		BtcWallet.SendPlan unowned = new BtcWallet.SendPlan(reviewed.toAddress,
				reviewed.amountSat, reviewed.feeSat, reviewed.netSat,
				reviewed.sweep, reviewed.outpoints, reviewed.fingerprint,
				reviewed.inputs, reviewed.outputs, reviewed.inputMetas,
				reviewed.hasChange, reviewed.changeCluster,
				reviewed.reusedOutpoints, reviewed.manual, reviewed.vbytes,
				reviewed.changeIndex);
		assertThrows(IOException.class, () -> after.signPlan(unowned));
		assertTrue(e.broadcasts.isEmpty());

		after.signPlan(after.planSend(DEST, 40000, 2.0, false, null, false));
		assertEquals(1, e.broadcasts.size());
	}

	@Test
	public void aSecondPrepareWhileAReviewIsOpenNeverReleasesTheOtherPlan()
			throws Exception {
		FakeElectrum e = funded();
		BtcWallet w = wallet(e);
		BtcWallet.SendPlan first = w.planSend(DEST, 40000, 2.0, false, null,
				false);
		BtcWallet.SendPlan second = w.planSend(OTHER, 30000, 2.0, false, null,
				false);
		assertNotEquals(first.fingerprint, second.fingerprint);
		SendGate gate = new SendGate();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		for (int round = 0; round < 200; round++) {
			CountDownLatch start = new CountDownLatch(1);
			Thread preparer = new Thread(() -> {
				try {
					start.await();
					gate.prepare(second, w.walletId());
				} catch (Throwable t) {
					failure.set(t);
				}
			});
			gate.prepare(first, w.walletId());
			preparer.start();
			start.countDown();
			BtcWallet.SendPlan released = null;
			try {
				released = gate.authorize(first.fingerprint, true,
						w.walletId());
			} catch (SendGate.AuthorizationException refused) {
			}
			preparer.join();
			if (released != null) {
				assertEquals(first.fingerprint, released.fingerprint);
				assertEquals(DEST, released.toAddress);
			}
			gate.clear();
		}
		assertNull(failure.get());
	}

	@Test
	public void plansCannotTravelThroughIntentsOrSavedState() {
		assertFalse(Serializable.class.isAssignableFrom(
				BtcWallet.SendPlan.class));
		assertFalse(Serializable.class.isAssignableFrom(
				BtcWallet.SpSweepPlan.class));
		assertFalse(android.os.Parcelable.class.isAssignableFrom(
				BtcWallet.SendPlan.class));
		assertFalse(android.os.Parcelable.class.isAssignableFrom(
				BtcWallet.SpSweepPlan.class));
	}

	private static SilentPaymentScanner.Found found(FakeElectrum e,
			String txid, long chainValue, long recordedValue) {
		byte[] xonly = new byte[32];
		xonly[31] = 1;
		byte[] spk = new byte[34];
		spk[0] = 0x51;
		spk[1] = 0x20;
		System.arraycopy(xonly, 0, spk, 2, 32);
		e.addUtxo(BtcKeys.scriptHashOfBytes(spk), txid, 0, chainValue);
		return new SilentPaymentScanner.Found(txid, 0, recordedValue, xonly,
				new byte[32]);
	}

	private static BtcWallet spWallet(FakeElectrum e) {
		return new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "primary.onion",
				50001, "walletA", (endpoint, socksPort, tag) -> e,
				(url, tag) -> null);
	}

	@Test
	public void aSweepIsBoundToItsWalletItsAmountsAndWhatWasShown()
			throws Exception {
		FakeElectrum e = new FakeElectrum();
		BtcWallet w = spWallet(e);
		BtcWallet.SpSweepPlan p = w.planSweepSilentPayments(
				Collections.singletonList(found(e, TX0, 100000, 100000)), DEST,
				5.0);
		BtcTx.TaprootInput in = p.inputs.get(0);
		List<BtcWallet.SpSweepPlan> attacks = new ArrayList<>();
		attacks.add(new BtcWallet.SpSweepPlan(OTHER, p.amountSat, p.feeSat,
				p.outpoints, p.fingerprint, p.inputs, p.outputs, p.spNetSat,
				p.owner));
		attacks.add(new BtcWallet.SpSweepPlan(DEST, p.amountSat - 1,
				p.feeSat + 1, p.outpoints, p.fingerprint, p.inputs, p.outputs,
				p.spNetSat, p.owner));
		attacks.add(new BtcWallet.SpSweepPlan(DEST, p.amountSat, p.feeSat,
				p.outpoints, p.fingerprint, p.inputs, p.outputs,
				p.spNetSat - 1, p.owner));
		List<BtcTx.TaprootInput> richer = Collections.singletonList(
				new BtcTx.TaprootInput(in.txHash, in.txPos, in.valueSat + 500,
						in.scriptPubKey, in.privKey));
		attacks.add(new BtcWallet.SpSweepPlan(DEST, p.amountSat,
				p.feeSat + 500, p.outpoints, p.fingerprint, richer, p.outputs,
				p.spNetSat - 500, p.owner));
		byte[] otherSpk = in.scriptPubKey.clone();
		otherSpk[33] ^= 1;
		attacks.add(new BtcWallet.SpSweepPlan(DEST, p.amountSat, p.feeSat,
				p.outpoints, p.fingerprint, Collections.singletonList(
						new BtcTx.TaprootInput(in.txHash, in.txPos, in.valueSat,
								otherSpk, in.privKey)), p.outputs, p.spNetSat,
				p.owner));
		attacks.add(new BtcWallet.SpSweepPlan(DEST, p.amountSat, p.feeSat,
				p.outpoints, p.fingerprint, p.inputs, p.outputs, p.spNetSat,
				null));
		attacks.add(new BtcWallet.SpSweepPlan(DEST, p.amountSat, p.feeSat,
				p.outpoints, p.fingerprint, p.inputs, p.outputs, p.spNetSat,
				spWallet(e).planSweepSilentPayments(Collections.singletonList(
						found(e, TX1, 100000, 100000)), DEST, 5.0).owner));
		for (BtcWallet.SpSweepPlan attack : attacks) {
			IOException refused = assertThrows(IOException.class,
					() -> w.signSpSweep(attack));
			assertEquals(BtcWallet.ERR_PLAN_CHANGED, refused.getMessage());
		}
		assertTrue(e.broadcasts.isEmpty());
	}

	@Test
	public void aSweepInputWhoseRecordedValueDisagreesWithTheChainIsNotUsed()
			throws Exception {
		FakeElectrum e = new FakeElectrum();
		BtcWallet w = spWallet(e);
		IOException none = assertThrows(IOException.class,
				() -> w.planSweepSilentPayments(Collections.singletonList(
						found(e, TX0, 100000, 250000)), DEST, 5.0));
		assertEquals(BtcWallet.ERR_SP_NONE, none.getMessage());
		BtcWallet.SpSweepPlan ok = w.planSweepSilentPayments(Arrays.asList(
				found(e, TX0, 100000, 250000), found(e, TX1, 80000, 80000)),
				DEST, 5.0);
		assertNotNull(ok);
		assertEquals(Collections.singletonList(TX1 + ":0"), ok.outpoints);
	}
}
