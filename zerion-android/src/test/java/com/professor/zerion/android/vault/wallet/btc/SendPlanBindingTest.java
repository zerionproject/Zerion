package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SendPlanBindingTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String OTHER =
			"bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String TX1 =
			"3333333333333333333333333333333333333333333333333333333333333333";

	private static BtcWallet wallet(FakeElectrum e, PendingLog log) {
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "host",
				50001, "walletA", new FakeElectrum.RecordingFactory(e),
				(url, tag) -> null);
		w.setPendingLog(log);
		return w;
	}

	private static FakeElectrum funded() {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		return e;
	}

	private static BtcWallet.SendPlan copy(BtcWallet.SendPlan p,
			List<String> outpoints, List<BtcTx.Input> inputs,
			List<BtcTx.Output> outputs, List<PrivacyMeta> metas,
			Set<String> reused) {
		return new BtcWallet.SendPlan(p.toAddress, p.amountSat, p.feeSat,
				p.netSat, p.sweep, outpoints, p.fingerprint, inputs, outputs,
				metas, p.hasChange, p.changeCluster, reused, p.manual,
				p.vbytes, p.changeIndex, p.owner);
	}

	private static List<String> paid(String rawHex) {
		Transaction tx = new Transaction(BtcKeys.PARAMS,
				Utils.HEX.decode(rawHex));
		List<String> out = new ArrayList<>();
		for (TransactionOutput o : tx.getOutputs()) {
			out.add(o.getScriptPubKey().getToAddress(BtcKeys.PARAMS, true)
					+ ":" + o.getValue().value);
		}
		Collections.sort(out);
		return out;
	}

	private static List<String> expected(List<BtcTx.Output> outputs) {
		List<String> out = new ArrayList<>();
		for (BtcTx.Output o : outputs) out.add(o.address + ":" + o.valueSat);
		Collections.sort(out);
		return out;
	}

	@Test
	public void changingTheListsAPlanWasBuiltFromChangesNothingThatIsSigned()
			throws Exception {
		FakeElectrum e = funded();
		BtcWallet w = wallet(e, new BroadcastDurabilityTest.MemLog());
		BtcWallet.SendPlan reviewed =
				w.planSend(DEST, 40000, 1.0, false, null, false);
		List<String> outpoints = new ArrayList<>(reviewed.outpoints);
		List<BtcTx.Input> inputs = new ArrayList<>(reviewed.inputs);
		List<BtcTx.Output> outputs = new ArrayList<>(reviewed.outputs);
		List<PrivacyMeta> metas = new ArrayList<>(reviewed.inputMetas);
		Set<String> reused = new HashSet<>(reviewed.reusedOutpoints);
		SendGate gate = new SendGate();
		gate.prepare(copy(reviewed, outpoints, inputs, outputs, metas, reused),
				"walletA");

		for (int i = 0; i < outputs.size(); i++) {
			outputs.set(i, new BtcTx.Output(OTHER, outputs.get(i).valueSat));
		}
		outputs.add(new BtcTx.Output(OTHER, 5000));
		outpoints.add(TX1 + ":0");
		metas.clear();
		reused.add(TX1 + ":0");

		BtcWallet.SendPlan approved =
				gate.authorize(reviewed.fingerprint, true, "walletA");
		assertEquals(reviewed.outpoints, approved.outpoints);
		assertEquals(reviewed.inputMetas, approved.inputMetas);
		assertEquals(reviewed.reusedOutpoints, approved.reusedOutpoints);
		w.signPlan(approved);
		assertEquals(1, e.broadcasts.size());
		assertEquals("the signed transaction pays exactly what was reviewed",
				expected(reviewed.outputs), paid(e.broadcasts.get(0)));
	}

	@Test
	public void aPlanCannotBeChangedThroughItsOwnCollections()
			throws IOException {
		BtcWallet w = wallet(funded(), PendingLog.NONE);
		BtcWallet.SendPlan p = w.planSend(DEST, 40000, 1.0, false, null, false);
		BtcTx.Output extra = new BtcTx.Output(OTHER, 5000);
		assertThrows(UnsupportedOperationException.class,
				() -> p.outputs.add(extra));
		assertThrows(UnsupportedOperationException.class,
				() -> p.outputs.set(0, extra));
		assertThrows(UnsupportedOperationException.class,
				() -> p.inputs.clear());
		assertThrows(UnsupportedOperationException.class,
				() -> p.outpoints.add(TX1 + ":0"));
		assertThrows(UnsupportedOperationException.class,
				() -> p.inputMetas.clear());
		assertThrows(UnsupportedOperationException.class,
				() -> p.reusedOutpoints.add(TX1 + ":0"));
	}

	@Test
	public void aPlanWhoseContentDiffersFromItsApprovedFingerprintIsNotSigned()
			throws Exception {
		FakeElectrum e = funded();
		BroadcastDurabilityTest.MemLog log =
				new BroadcastDurabilityTest.MemLog();
		BtcWallet w = wallet(e, log);
		BtcWallet.SendPlan reviewed =
				w.planSend(DEST, 40000, 1.0, false, null, false);
		BtcTx.Input in = reviewed.inputs.get(0);
		int changeProbe = w.minChangeProbe();
		List<BtcWallet.SendPlan> changed = new ArrayList<>();

		List<BtcTx.Output> redirected = new ArrayList<>();
		for (BtcTx.Output o : reviewed.outputs) {
			redirected.add(new BtcTx.Output(
					o.address.equals(DEST) ? OTHER : o.address, o.valueSat));
		}
		changed.add(copy(reviewed, reviewed.outpoints, reviewed.inputs,
				redirected, reviewed.inputMetas, reviewed.reusedOutpoints));

		List<BtcTx.Output> raised = new ArrayList<>();
		for (BtcTx.Output o : reviewed.outputs) {
			raised.add(new BtcTx.Output(o.address,
					o.address.equals(DEST) ? o.valueSat + 1000 : o.valueSat));
		}
		changed.add(copy(reviewed, reviewed.outpoints, reviewed.inputs,
				raised, reviewed.inputMetas, reviewed.reusedOutpoints));

		List<BtcTx.Output> added = new ArrayList<>(reviewed.outputs);
		added.add(new BtcTx.Output(OTHER, 5000));
		changed.add(copy(reviewed, reviewed.outpoints, reviewed.inputs,
				added, reviewed.inputMetas, reviewed.reusedOutpoints));

		List<BtcTx.Input> swapped = Arrays.asList(
				new BtcTx.Input(TX1, in.txPos, in.valueSat, in.key));
		changed.add(copy(reviewed, reviewed.outpoints, swapped,
				reviewed.outputs, reviewed.inputMetas,
				reviewed.reusedOutpoints));

		changed.add(copy(reviewed, Arrays.asList(TX1 + ":0"),
				reviewed.inputs, reviewed.outputs, reviewed.inputMetas,
				reviewed.reusedOutpoints));

		for (BtcWallet.SendPlan p : changed) {
			SendGate gate = new SendGate();
			gate.prepare(p, "walletA");
			BtcWallet.SendPlan approved =
					gate.authorize(reviewed.fingerprint, true, "walletA");
			IOException refused = assertThrows(IOException.class,
					() -> w.signPlan(approved));
			assertFalse(refused instanceof BroadcastUncertainException);
			assertEquals(BtcWallet.ERR_PLAN_CHANGED, refused.getMessage());
		}
		assertTrue("nothing was broadcast", e.broadcasts.isEmpty());
		assertTrue("nothing was recorded as pending", log.map.isEmpty());
		assertEquals(changeProbe, w.minChangeProbe());

		w.signPlan(reviewed);
		assertEquals(1, e.broadcasts.size());
	}

	@Test
	public void aSweepPlanKeepsItsOwnCopyOfEverythingItSigns() {
		byte[] spk = new byte[34];
		spk[0] = 0x51;
		spk[1] = 0x20;
		List<String> outpoints = new ArrayList<>(Arrays.asList(TX0 + ":0"));
		List<BtcTx.TaprootInput> inputs = new ArrayList<>();
		inputs.add(new BtcTx.TaprootInput(TX0, 0, 100000, spk,
				BigInteger.ONE));
		List<BtcTx.Output> outputs = new ArrayList<>();
		outputs.add(new BtcTx.Output(DEST, 99000));
		BtcWallet.SpSweepPlan plan = new BtcWallet.SpSweepPlan(DEST, 99000,
				1000, outpoints, "A", inputs, outputs, -100000);
		byte[] reviewedSpk = spk.clone();

		spk[5] = 7;
		inputs.clear();
		outputs.set(0, new BtcTx.Output(OTHER, 99000));
		outpoints.clear();

		assertEquals(Arrays.asList(TX0 + ":0"), plan.outpoints);
		assertEquals(1, plan.inputs.size());
		assertArrayEquals(reviewedSpk, plan.inputs.get(0).scriptPubKey);
		assertEquals(DEST, plan.outputs.get(0).address);
		assertThrows(UnsupportedOperationException.class,
				() -> plan.inputs.clear());
		assertThrows(UnsupportedOperationException.class,
				() -> plan.outputs.add(new BtcTx.Output(OTHER, 1)));
		assertThrows(UnsupportedOperationException.class,
				() -> plan.outpoints.add(TX1 + ":0"));
	}

	@Test
	public void aSweepWhoseContentDiffersFromItsApprovedFingerprintIsNotSigned()
			throws Exception {
		FakeElectrum e = new FakeElectrum();
		byte[] xonly = new byte[32];
		xonly[31] = 1;
		byte[] spk = new byte[34];
		spk[0] = 0x51;
		spk[1] = 0x20;
		System.arraycopy(xonly, 0, spk, 2, 32);
		e.addUtxo(BtcKeys.scriptHashOfBytes(spk), TX0, 0, 100000);
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999,
				"primary.onion", 50001, "walletA",
				(endpoint, socksPort, tag) -> e, (url, tag) -> null);
		BtcWallet.SpSweepPlan reviewed = w.planSweepSilentPayments(
				Collections.singletonList(new SilentPaymentScanner.Found(TX0, 0,
						100000, xonly, new byte[32])), DEST, 5.0);
		List<BtcTx.Output> redirected = new ArrayList<>();
		for (BtcTx.Output o : reviewed.outputs) {
			redirected.add(new BtcTx.Output(OTHER, o.valueSat));
		}
		BtcWallet.SpSweepPlan changed = new BtcWallet.SpSweepPlan(
				reviewed.toAddress, reviewed.amountSat, reviewed.feeSat,
				reviewed.outpoints, reviewed.fingerprint, reviewed.inputs,
				redirected, reviewed.spNetSat, reviewed.owner);
		SpSweepGate gate = new SpSweepGate();
		gate.prepare(changed, "walletA");
		BtcWallet.SpSweepPlan approved =
				gate.authorize(reviewed.fingerprint, true, "walletA");
		IOException refused = assertThrows(IOException.class,
				() -> w.signSpSweep(approved));
		assertEquals(BtcWallet.ERR_PLAN_CHANGED, refused.getMessage());
		assertTrue("nothing was broadcast", e.broadcasts.isEmpty());
	}
}
