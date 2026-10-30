package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.professor.zerion.android.vault.InMemoryVault;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.junit.Test;

import java.io.IOException;

public class FeeGuardTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final double NONE = Double.NaN;

	private static final class ManualClock implements FeeGuard.Clock {
		long now = 1_000_000L;

		@Override
		public long now() {
			return now;
		}
	}

	private static final class MemoryStore implements FeeGuard.Store {
		String json;

		@Override
		public String load() {
			return json;
		}

		@Override
		public void save(String json) {
			this.json = json;
		}
	}

	private static FeeGuard guard(ManualClock clock, FeeGuard.Store store) {
		FeeGuard g = new FeeGuard();
		g.setClock(clock);
		g.setStore(store);
		return g;
	}

	private static boolean shownAsJump(FeeGuard g, int target, double rate) {
		g.offer(target, rate);
		return g.review(rate).jump;
	}

	@Test
	public void ratchetOverQuickRefreshesKeepsWarning() {
		ManualClock clock = new ManualClock();
		FeeGuard g = guard(clock, new MemoryStore());
		for (double r : new double[]{5, 15, 45, 100}) {
			clock.now += 60_000L;
			g.accept(3, r, NONE);
		}
		assertTrue(shownAsJump(g, 3, 100));
		clock.now += 60_000L;
		g.accept(3, 45, NONE);
		assertTrue("45 is still nine times the slowly learned baseline",
				shownAsJump(g, 3, 45));
	}

	@Test
	public void eachStepOfTheRatchetIsCheckedAgainstTheSlowBaseline() {
		ManualClock clock = new ManualClock();
		FeeGuard g = guard(clock, new MemoryStore());
		g.accept(3, 5, NONE);
		clock.now += 60_000L;
		g.accept(3, 15, NONE);
		assertFalse(shownAsJump(g, 3, 15));
		clock.now += 60_000L;
		g.accept(3, 45, NONE);
		assertTrue(shownAsJump(g, 3, 45));
		clock.now += 60_000L;
		g.accept(3, 100, NONE);
		assertTrue(shownAsJump(g, 3, 100));
	}

	@Test
	public void upwardLearningIsAtMostOneAndAHalfPerHour() {
		ManualClock clock = new ManualClock();
		FeeGuard g = guard(clock, new MemoryStore());
		g.accept(3, 10, NONE);
		clock.now += FeeGuard.HOUR_MS;
		g.accept(3, 100, NONE);
		clock.now += 1;
		g.accept(3, 100, NONE);
		g.offer(3, 46);
		assertTrue("baseline is 15 after one hour", g.review(46).jump);
		g.offer(3, 44);
		assertFalse(g.review(44).jump);
	}

	@Test
	public void sustainedRiseIsLearnedOverHours() {
		ManualClock clock = new ManualClock();
		FeeGuard g = guard(clock, new MemoryStore());
		g.accept(3, 10, NONE);
		for (int h = 0; h < 6; h++) {
			clock.now += FeeGuard.HOUR_MS;
			g.accept(3, 100, NONE);
		}
		assertFalse(shownAsJump(g, 3, 100));
	}

	@Test
	public void lowerRatesAreLearnedAtOnce() {
		ManualClock clock = new ManualClock();
		FeeGuard g = guard(clock, new MemoryStore());
		g.accept(3, 30, NONE);
		g.accept(3, 5, NONE);
		g.accept(3, 5, NONE);
		assertTrue(shownAsJump(g, 3, 30));
	}

	@Test
	public void agreeingSecondServerIsLearnedAtOnce() {
		ManualClock clock = new ManualClock();
		FeeGuard g = guard(clock, new MemoryStore());
		g.accept(3, 10, 10);
		g.accept(3, 50, 60);
		assertTrue("the jump is still shown once", shownAsJump(g, 3, 50));
		g.accept(3, 50, 60);
		assertFalse(shownAsJump(g, 3, 50));
	}

	@Test
	public void disagreeingSecondServerIsNotLearnedAtOnce() {
		ManualClock clock = new ManualClock();
		FeeGuard g = guard(clock, new MemoryStore());
		g.accept(3, 10, 10);
		g.accept(3, 50, 200);
		g.accept(3, 50, 200);
		g.offer(3, 50);
		FeeGuard.Verdict v = g.review(50);
		assertTrue(v.jump);
		assertTrue(v.disagree);
	}

	@Test
	public void baselineSurvivesARestart() {
		ManualClock clock = new ManualClock();
		MemoryStore store = new MemoryStore();
		guard(clock, store).accept(3, 5, NONE);
		FeeGuard restarted = guard(clock, store);
		clock.now += 60_000L;
		restarted.accept(3, 45, NONE);
		assertTrue("the first reading after a start is checked",
				shownAsJump(restarted, 3, 45));
	}

	@Test
	public void baselineSurvivesARestartThroughTheWalletSettings()
			throws Exception {
		BtcWalletSettings settings = new BtcWalletSettings(
				new WalletStore(new InMemoryVault().manager));
		ManualClock clock = new ManualClock();
		guard(clock, settings.feeBaselineStore("w")).accept(3, 5, NONE);
		assertTrue(settings.read().getJSONObject("fee").has("w"));
		FeeGuard restarted = guard(clock, settings.feeBaselineStore("w"));
		restarted.accept(3, 45, NONE);
		assertTrue(shownAsJump(restarted, 3, 45));
		FeeGuard other = guard(clock, settings.feeBaselineStore("x"));
		other.accept(3, 45, NONE);
		assertFalse("baselines are per wallet", shownAsJump(other, 3, 45));
	}

	@Test
	public void theChosenRateIsComparedWithItsOwnTarget() {
		ManualClock clock = new ManualClock();
		FeeGuard g = guard(clock, new MemoryStore());
		g.accept(1, 40, NONE);
		g.accept(6, 5, NONE);
		g.accept(1, 40, NONE);
		g.accept(6, 20, NONE);
		g.offer(1, 40);
		g.offer(6, 20);
		assertTrue("economy 20 against its own baseline of 5",
				g.review(20).jump);
		assertFalse(g.review(40).jump);
	}

	@Test
	public void walletRatchetWarnsAtReview() throws IOException {
		FakeElectrum e = new FakeElectrum();
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "host",
				50001, "w", new FakeElectrum.RecordingFactory(e),
				(url, tag) -> null);
		ManualClock clock = new ManualClock();
		w.setFeeClock(clock);
		MemoryStore store = new MemoryStore();
		w.setFeeBaselineStore(store);
		double last = 0;
		for (double btcPerKb : new double[]{0.00005, 0.00015, 0.00045,
				0.001}) {
			clock.now += 60_000L;
			e.feeBtcPerKb = btcPerKb;
			last = w.feeOptions()[1];
		}
		assertEquals(100.0, last, 1e-9);
		assertTrue(w.feeReview(last).jump);
		BtcWallet restarted = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999,
				"host", 50001, "w", new FakeElectrum.RecordingFactory(e),
				(url, tag) -> null);
		restarted.setFeeClock(clock);
		restarted.setFeeBaselineStore(store);
		assertEquals(100.0, restarted.feeOptions()[1], 1e-9);
		assertTrue("restart keeps the baseline",
				restarted.feeReview(100.0).jump);
	}
}
