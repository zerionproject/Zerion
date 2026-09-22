package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * A Silent Payments sweep goes through plan, review and a fingerprint-bound
 * authorisation like every other send: planning shows the destination,
 * amount and fee without signing or broadcasting anything, the fingerprint
 * covers what was shown, and the gate releases the plan only for that
 * fingerprint.
 */
public class SpSweepPlanTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DESTINATION =
			"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
	private static final String OTHER_DESTINATION =
			"bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3";

	private static BtcWallet wallet(FakeElectrum backend) {
		return new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "primary.onion", 50001,
				"walletA", (endpoint, socksPort, tag) -> backend,
				(url, tag) -> null);
	}

	private static SilentPaymentScanner.Found found(FakeElectrum backend,
			String txid, long valueSat) {
		byte[] xonly = new byte[32];
		byte[] tweak = new byte[32];
		new SecureRandom().nextBytes(xonly);
		new SecureRandom().nextBytes(tweak);
		byte[] spk = new byte[34];
		spk[0] = 0x51;
		spk[1] = 0x20;
		System.arraycopy(xonly, 0, spk, 2, 32);
		backend.addUtxo(BtcKeys.scriptHashOfBytes(spk), txid, 0, valueSat);
		return new SilentPaymentScanner.Found(txid, 0, valueSat, xonly, tweak);
	}

	@Test
	public void planningShowsTheTransactionWithoutBroadcasting()
			throws Exception {
		FakeElectrum backend = new FakeElectrum();
		BtcWallet w = wallet(backend);
		String txid = "aa".repeat(32);
		List<SilentPaymentScanner.Found> utxos =
				Collections.singletonList(found(backend, txid, 100_000));
		BtcWallet.SpSweepPlan plan =
				w.planSweepSilentPayments(utxos, DESTINATION, 5.0);
		assertEquals(DESTINATION, plan.toAddress);
		long expectedFee = (long) Math.ceil((11 + 58 + 31) * 5.0);
		assertEquals(expectedFee, plan.feeSat);
		assertEquals(100_000 - expectedFee, plan.amountSat);
		assertEquals(Arrays.asList(txid + ":0"), plan.outpoints);
		assertEquals(64, plan.fingerprint.length());
		assertTrue("planning must not broadcast", backend.broadcasts.isEmpty());
	}

	@Test
	public void fingerprintCoversDestinationAmountAndInputs()
			throws Exception {
		FakeElectrum backend = new FakeElectrum();
		BtcWallet w = wallet(backend);
		List<SilentPaymentScanner.Found> utxos = new ArrayList<>();
		utxos.add(found(backend, "aa".repeat(32), 100_000));
		String base = w.planSweepSilentPayments(utxos, DESTINATION, 5.0)
				.fingerprint;
		assertEquals("the same review yields the same fingerprint", base,
				w.planSweepSilentPayments(utxos, DESTINATION, 5.0).fingerprint);
		assertNotEquals("another destination",
				base, w.planSweepSilentPayments(utxos, OTHER_DESTINATION, 5.0)
						.fingerprint);
		assertNotEquals("another fee changes the amount", base,
				w.planSweepSilentPayments(utxos, DESTINATION, 20.0)
						.fingerprint);
		utxos.add(found(backend, "bb".repeat(32), 50_000));
		assertNotEquals("another input set", base,
				w.planSweepSilentPayments(utxos, DESTINATION, 5.0)
						.fingerprint);
	}

	private static BtcWallet.SpSweepPlan plan(String fingerprint) {
		return new BtcWallet.SpSweepPlan(DESTINATION, 100, 10,
				Arrays.asList("t:0"), fingerprint, new ArrayList<>(),
				new ArrayList<>(), -110);
	}

	@Test
	public void gateReleasesOnlyTheReviewedPlanExactlyOnce() throws Exception {
		SpSweepGate g = new SpSweepGate();
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true));
		BtcWallet.SpSweepPlan a = plan("A");
		g.prepare(a);
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", false));
		assertSame(a, g.authorize("A", true));
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true));
	}

	@Test
	public void gateRefusesAChangedOrClearedPlan() {
		SpSweepGate g = new SpSweepGate();
		g.prepare(plan("A"));
		g.prepare(plan("B"));
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true));
		assertNull(g.pending());
		g.prepare(plan("C"));
		g.clear();
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("C", true));
	}
}
