package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FeeCrossCheckTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String SCAN_ONION =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion";
	private static final String BCAST_ONION =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion";

	private static final class TwoServers implements ElectrumRpc.Factory {
		final FakeElectrum scan = new FakeElectrum();
		final FakeElectrum other = new FakeElectrum();
		boolean otherDown;
		long otherDelayMs;
		int otherOpens;
		final List<String> tags = new ArrayList<>();

		@Override
		public ElectrumRpc open(ElectrumEndpoint endpoint, int socksPort,
				String isolationTag) throws IOException {
			tags.add(isolationTag);
			if (endpoint.host.equals(SCAN_ONION)) return scan;
			otherOpens++;
			if (otherDown) throw new IOException("unreachable");
			if (otherDelayMs > 0) {
				try {
					Thread.sleep(otherDelayMs);
				} catch (InterruptedException e) {
					throw new IOException("interrupted");
				}
			}
			return other;
		}
	}

	private static final class ManualClock implements FeeGuard.Clock {
		long now = 1_000_000L;

		@Override
		public long now() {
			return now;
		}
	}

	private static BtcWallet wallet(TwoServers f, String broadcastHost) {
		return new BtcWallet(MNEMONIC.toCharArray(), 0, 9999,
				new ElectrumEndpoint(SCAN_ONION, 50001,
						ElectrumEndpoint.Mode.ONION, false, null),
				new ElectrumEndpoint(broadcastHost, 50001,
						ElectrumEndpoint.Mode.ONION, false, null),
				"w", f, (url, tag) -> null);
	}

	@Test
	public void hostileScanServerIsOverruledBySecondServer()
			throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 0.002;
		f.other.feeBtcPerKb = 0.0001;
		BtcWallet w = wallet(f, BCAST_ONION);
		for (double r : w.feeOptions()) assertEquals(10.0, r, 1e-9);
		assertTrue("estimates 10x apart are flagged",
				w.feeReview(10.0).serversDisagree);
	}

	@Test
	public void lowerServerWinsAndAWideGapIsFlagged() throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 0.00005;
		f.other.feeBtcPerKb = 0.0003;
		BtcWallet w = wallet(f, BCAST_ONION);
		assertEquals(5.0, w.feeOptions()[1], 1e-9);
		assertTrue(w.feeReview(5.0).serversDisagree);
	}

	@Test
	public void closeEstimatesAreNotFlagged() throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 0.0001;
		f.other.feeBtcPerKb = 0.00025;
		BtcWallet w = wallet(f, BCAST_ONION);
		assertEquals(10.0, w.feeOptions()[1], 1e-9);
		assertFalse(w.feeReview(10.0).serversDisagree);
	}

	@Test
	public void crossCheckUsesItsOwnTorCircuit() throws IOException {
		TwoServers f = new TwoServers();
		wallet(f, BCAST_ONION).feeOptions();
		assertTrue(f.tags.contains(TorIsolation.feeCheck("w")));
		assertFalse(f.tags.contains(TorIsolation.broadcast("w")));
	}

	@Test
	public void scanRefreshDoesNotCrossCheck() throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 0.0002;
		f.other.feeBtcPerKb = 0.0001;
		BtcWallet w = wallet(f, BCAST_ONION);
		assertEquals(20.0, w.feeRateSatPerVb(4), 1e-9);
		assertEquals(0, f.otherOpens);
	}

	@Test
	public void secondOpinionIsCachedForAFewMinutes() throws IOException {
		TwoServers f = new TwoServers();
		ManualClock clock = new ManualClock();
		BtcWallet w = wallet(f, BCAST_ONION);
		w.setFeeClock(clock);
		w.feeOptions();
		w.feeOptions();
		assertEquals(1, f.otherOpens);
		clock.now += BtcWallet.SECOND_OPINION_CACHE_MS;
		w.feeOptions();
		assertEquals(2, f.otherOpens);
	}

	@Test
	public void slowSecondServerIsAbandonedAtTheDeadline() throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 0.0002;
		f.other.feeBtcPerKb = 0.0001;
		f.otherDelayMs = 5_000;
		BtcWallet w = wallet(f, BCAST_ONION);
		w.secondOpinionDeadlineMs = 200;
		long start = System.nanoTime();
		assertEquals(20.0, w.feeOptions()[1], 1e-9);
		assertTrue((System.nanoTime() - start) / 1_000_000L < 3_000);
		assertEquals(8_000L, BtcWallet.SECOND_OPINION_DEADLINE_MS);
	}

	@Test
	public void unreachableSecondServerFallsBackToPrimary()
			throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 0.0002;
		f.otherDown = true;
		assertEquals(20.0, wallet(f, BCAST_ONION).feeOptions()[1], 1e-9);
	}

	@Test
	public void unavailableSecondEstimateIsNoOpinion() throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 0.0002;
		for (double bad : new double[]{-1, 0, Double.NaN}) {
			f.other.feeBtcPerKb = bad;
			BtcWallet w = wallet(f, BCAST_ONION);
			assertEquals(20.0, w.feeOptions()[1], 1e-9);
			assertFalse(w.feeReview(20.0).serversDisagree);
		}
	}

	@Test
	public void unavailablePrimaryEstimateUsesTheSecond() throws IOException {
		TwoServers f = new TwoServers();
		f.other.feeBtcPerKb = 0.00015;
		for (double bad : new double[]{-1, 0, Double.NaN,
				Double.POSITIVE_INFINITY}) {
			f.scan.feeBtcPerKb = bad;
			assertEquals(15.0, wallet(f, BCAST_ONION).feeOptions()[1], 1e-9);
		}
	}

	@Test
	public void noEstimateAnywhereTakesTheFloor() throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = -1;
		f.otherDown = true;
		assertEquals(BtcWallet.MIN_FEE_RATE,
				wallet(f, BCAST_ONION).feeOptions()[1], 1e-9);
	}

	@Test
	public void sameServerIsNotASecondOpinion() throws IOException {
		TwoServers f = new TwoServers();
		BtcWallet w = wallet(f, SCAN_ONION);
		assertNull(w.feeCheckEndpoint());
		w.feeOptions();
		assertFalse(f.tags.contains(TorIsolation.feeCheck("w")));
	}

	@Test
	public void anExplicitFeeCheckServerIsUsed() throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 0.002;
		f.other.feeBtcPerKb = 0.0001;
		BtcWallet w = wallet(f, SCAN_ONION);
		w.setFeeCheckEndpoint(new ElectrumEndpoint(BCAST_ONION, 50001,
				ElectrumEndpoint.Mode.ONION, false, null));
		assertEquals(10.0, w.feeOptions()[1], 1e-9);
		assertTrue(f.tags.contains(TorIsolation.feeCheck("w")));
	}

	@Test
	public void hardCapIsLowered() throws IOException {
		TwoServers f = new TwoServers();
		f.scan.feeBtcPerKb = 1.0;
		f.otherDown = true;
		assertEquals(100.0, wallet(f, BCAST_ONION).feeRateSatPerVb(1), 1e-9);
	}
}
