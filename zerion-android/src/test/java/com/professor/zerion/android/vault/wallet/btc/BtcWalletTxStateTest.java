package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class BtcWalletTxStateTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String DEST2 =
			"bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static BtcWallet wallet(FakeElectrum e, PendingLog log) {
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPendingLog(log);
		return w;
	}

	private static FakeElectrum funded() {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		return e;
	}

	@Test
	public void planSendDoesNotBroadcast() throws IOException {
		FakeElectrum e = funded();
		BtcWallet.SendPlan p = wallet(e, PendingLog.NONE)
				.planSend(DEST, 40000, 1.0, false, null, false);
		assertEquals(40000, p.amountSat);
		assertTrue(p.feeSat > 0);
		assertEquals(1, p.outpoints.size());
		assertTrue(p.netSat < 0);
		assertTrue(e.broadcasts.isEmpty());
	}

	@Test
	public void signPlanBroadcastsTheExactPlan() throws IOException {
		FakeElectrum e = funded();
		BtcWallet w = wallet(e, new BroadcastDurabilityTest.MemLog());
		BtcWallet.SendPlan p = w.planSend(DEST, 40000, 1.0, false, null, false);
		String txid = w.signPlan(p);
		assertEquals(e.lastBroadcastTxid(), txid);
		assertEquals(1, e.broadcasts.size());
	}

	@Test
	public void fingerprintChangesWithEveryParameter() throws IOException {
		BtcWallet w = wallet(funded(), PendingLog.NONE);
		String a = w.planSend(DEST, 40000, 1.0, false, null, false).fingerprint;
		String amt = w.planSend(DEST, 41000, 1.0, false, null, false).fingerprint;
		String dst = w.planSend(DEST2, 40000, 1.0, false, null, false).fingerprint;
		String fee = w.planSend(DEST, 40000, 8.0, false, null, false).fingerprint;
		assertNotEquals(a, amt);
		assertNotEquals(a, dst);
		assertNotEquals(a, fee);
	}

	@Test
	public void outgoingAppearsImmediatelyAsPendingAfterAck()
			throws IOException {
		FakeElectrum e = funded();
		BtcWallet w = wallet(e, new BroadcastDurabilityTest.MemLog());
		w.send(DEST, 40000, 1.0, false);
		List<BtcWallet.TxSummary> ps = w.pendingSummaries();
		assertEquals(1, ps.size());
		assertEquals(BtcWallet.STATE_PENDING, ps.get(0).state);
		assertTrue(ps.get(0).netSat < 0);
	}

	@Test
	public void lostAckAppearsImmediatelyAsPossiblySent() {
		FakeElectrum e = funded();
		e.broadcastError = new IOException("connection closed");
		BtcWallet w = wallet(e, new BroadcastDurabilityTest.MemLog());
		org.junit.Assert.assertThrows(BroadcastUncertainException.class,
				() -> w.send(DEST, 40000, 1.0, false));
		List<BtcWallet.TxSummary> ps = w.pendingSummaries();
		assertEquals(BtcWallet.STATE_POSSIBLY_SENT, ps.get(0).state);
	}

	@Test
	public void mergePendingDedupesByTxidElectrumWins() {
		BtcWallet.TxSummary confirmed = new BtcWallet.TxSummary("X", 800000, 100,
				3, true, BtcWallet.STATE_CONFIRMED);
		BtcWallet.TxSummary localX = new BtcWallet.TxSummary("X", 0, -100, 0,
				true, BtcWallet.STATE_POSSIBLY_SENT);
		BtcWallet.TxSummary localY = new BtcWallet.TxSummary("Y", 0, -50, 0,
				true, BtcWallet.STATE_BROADCASTING);
		List<BtcWallet.TxSummary> merged = BtcWallet.mergePending(
				Arrays.asList(confirmed), Arrays.asList(localX, localY));
		assertEquals(2, merged.size());
		assertEquals("Y", merged.get(0).txid);
		assertEquals("X", merged.get(1).txid);
		assertEquals(BtcWallet.STATE_CONFIRMED, merged.get(1).state);
	}
}
