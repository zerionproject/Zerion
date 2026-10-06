package com.professor.zerion.android.navdrawer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TorPublishStateTest {

	private static final long G = 60_000;

	private static final class Sim {
		final TorPublishState s = new TorPublishState(G);
		long now = 0;
		long checkAt = -1;
		long transportSkew = 0;

		void schedule(long delay) {
			checkAt = delay == TorPublishState.NO_CHECK ? -1 : now + delay;
		}

		void active() {
			schedule(s.onActive(now, transportSkew));
		}

		void drop(boolean fullStop) {
			schedule(s.onInactive(fullStop, now));
		}

		void skew(long seconds) {
			transportSkew = seconds;
			schedule(s.onClockSkew(seconds));
		}

		void proof() {
			checkAt = -1;
			s.onPublishProof();
		}

		void advance(long ms) {
			long target = now + ms;
			while (checkAt >= 0 && checkAt <= target) {
				now = checkAt;
				schedule(s.onCheck(now, transportSkew));
			}
			now = target;
		}

		void doze(long ms) {
			now += ms;
		}

		void wakeCheck() {
			schedule(s.onCheck(now, transportSkew));
		}

		void wake() {
			if (checkAt >= 0 && checkAt <= now) {
				schedule(s.onCheck(now, transportSkew));
			}
		}
	}

	@Test
	public void aStableConnectionIsPublishedExactlyAtTheGrace() {
		Sim m = new Sim();
		m.active();
		m.advance(G - 1);
		assertFalse(m.s.isPublished());
		m.advance(1);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void flappingFasterThanTheGraceStillPublishesAtTheDeadline() {
		Sim m = new Sim();
		m.active();
		for (int i = 0; i < 2; i++) {
			m.advance(20_000);
			m.drop(false);
			m.advance(5_000);
			m.active();
		}
		assertEquals(50_000, m.now);
		assertFalse(m.s.isPublished());
		assertEquals("the pending check still targets the first deadline",
				G, m.checkAt);
		m.advance(10_000);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void flappingEveryFewSecondsForMinutesNeverRestartsTheWait() {
		Sim m = new Sim();
		m.active();
		for (int i = 0; i < 60 && !m.s.isPublished(); i++) {
			m.advance(3_000);
			m.drop(false);
			m.advance(1_000);
			m.active();
			m.advance(0);
		}
		assertTrue("a plugin flapping every 4 s is published, not stuck",
				m.s.isPublished());
		assertTrue(m.now <= G + 4_000);
	}

	@Test
	public void repeatedActiveEventsDoNotRestartTheWait() {
		Sim m = new Sim();
		m.active();
		m.advance(30_000);
		m.active();
		m.active();
		assertEquals(G, m.checkAt);
		m.advance(30_000);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void aDozeDelayedCheckPublishesWhenItFinallyRunsAndNotBefore() {
		Sim m = new Sim();
		m.active();
		m.doze(5 * 60_000);
		assertFalse("nothing happens while no check runs", m.s.isPublished());
		m.wake();
		assertTrue(m.s.isPublished());
	}

	@Test
	public void aDozeThatDropsTorThenReturnsAfterTheGraceWaitsOneShortRegrace() {
		Sim m = new Sim();
		m.active();
		m.advance(10_000);
		m.drop(false);
		m.doze(10 * 60_000);
		m.active();
		assertEquals(m.now + TorPublishState.REPUBLISH_GRACE_MS, m.checkAt);
		m.advance(TorPublishState.REPUBLISH_GRACE_MS - 1);
		assertFalse(m.s.isPublished());
		m.advance(1);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void aReconnectBeforeTheGraceWaitsOnlyTheRemainder() {
		Sim m = new Sim();
		m.active();
		m.advance(40_000);
		m.drop(false);
		m.advance(10_000);
		m.active();
		assertEquals(G, m.checkAt);
		m.advance(9_999);
		assertFalse(m.s.isPublished());
		m.advance(1);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void aDisableAndReEnableStartsAFreshFullGrace() {
		Sim m = new Sim();
		m.active();
		m.advance(G + 10_000);
		assertTrue(m.s.isPublished());
		m.drop(true);
		assertFalse(m.s.isPublished());
		m.advance(10_000);
		m.active();
		m.advance(G - 1);
		assertFalse(m.s.isPublished());
		m.advance(1);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void aBlipShorterThanTheRegraceRepublishesAtOnce() {
		Sim m = new Sim();
		m.active();
		m.advance(G);
		assertTrue(m.s.isPublished());
		m.drop(false);
		assertFalse(m.s.isPublished());
		m.advance(TorPublishState.REPUBLISH_GRACE_MS - 1);
		m.active();
		m.advance(0);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void anOutageLongerThanTheRegraceWaitsOneShortRegrace() {
		Sim m = new Sim();
		m.active();
		m.advance(G);
		assertTrue(m.s.isPublished());
		m.drop(false);
		m.advance(TorPublishState.REPUBLISH_GRACE_MS);
		m.active();
		m.advance(0);
		assertFalse("Tor coming back after a long outage is not instant proof",
				m.s.isPublished());
		for (int i = 0; i < 3; i++) {
			m.advance(4_000);
			m.drop(false);
			m.advance(1_000);
			m.active();
		}
		assertFalse(m.s.isPublished());
		m.advance(TorPublishState.REPUBLISH_GRACE_MS);
		assertTrue("the re-grace is fixed at the first return, flaps do not restart it",
				m.s.isPublished());
	}

	@Test
	public void anInboundConnectionPublishesBeforeTheGrace() {
		Sim m = new Sim();
		m.active();
		m.advance(5_000);
		m.proof();
		assertTrue(m.s.isPublished());
		assertEquals(-1, m.checkAt);
	}

	@Test
	public void aClockSkewPresentAtStartNeverLetsTheGracePublish() {
		Sim m = new Sim();
		m.transportSkew = 3600;
		m.active();
		m.advance(30 * 60_000);
		assertFalse(m.s.isPublished());
		assertEquals(3600, m.s.getSkewSeconds());
	}

	@Test
	public void aClockSkewReportedDuringTheGraceBlocksIt() {
		Sim m = new Sim();
		m.active();
		m.advance(30_000);
		m.skew(-7200);
		m.advance(10 * 60_000);
		assertFalse(m.s.isPublished());
		assertEquals(-7200, m.s.getSkewSeconds());
	}

	@Test
	public void aClockSkewAfterPublicationUnpublishes() {
		Sim m = new Sim();
		m.active();
		m.advance(G);
		assertTrue(m.s.isPublished());
		m.skew(3600);
		assertFalse(m.s.isPublished());
		assertEquals(3600, m.s.getSkewSeconds());
	}

	@Test
	public void aCorrectedClockGetsAFreshFullGraceNotAnInstantPublish() {
		Sim m = new Sim();
		m.active();
		m.advance(10_000);
		m.skew(-7200);
		m.advance(5 * 60_000);
		assertFalse(m.s.isPublished());
		m.transportSkew = 0;
		m.advance(TorPublishState.SKEW_RECHECK_MS);
		assertEquals("the shown skew is cleared at the next recheck", 0,
				m.s.getSkewSeconds());
		assertFalse("but the address is not assumed published at once",
				m.s.isPublished());
		long clearedAt = m.checkAt - G;
		m.advance(clearedAt + G - 1 - m.now);
		assertFalse(m.s.isPublished());
		m.advance(1);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void aPublishProofClearsTheSkewAndPublishes() {
		Sim m = new Sim();
		m.active();
		m.skew(3600);
		m.proof();
		assertTrue(m.s.isPublished());
		assertEquals(0, m.s.getSkewSeconds());
	}

	@Test
	public void aTransientDropKeepsTheSkewAndAFullStopClearsIt() {
		Sim m = new Sim();
		m.active();
		m.skew(3600);
		m.drop(false);
		assertEquals(3600, m.s.getSkewSeconds());
		m.drop(true);
		assertEquals(0, m.s.getSkewSeconds());
	}

	@Test
	public void aStillWrongClockAfterADropIsShownAgainAndNotPublished() {
		Sim m = new Sim();
		m.active();
		m.advance(G + 1_000);
		m.skew(3600);
		m.drop(false);
		m.advance(5_000);
		m.active();
		m.advance(10 * 60_000);
		assertFalse("the passed deadline must not publish over a live skew",
				m.s.isPublished());
		assertEquals(3600, m.s.getSkewSeconds());
	}

	@Test
	public void aSkewReportedWhileTorIsNotActiveIsShownAndRechecked() {
		Sim m = new Sim();
		m.skew(3600);
		assertEquals(TorPublishState.SKEW_RECHECK_MS, m.checkAt);
		assertEquals(3600, m.s.getSkewSeconds());
		assertFalse(m.s.isPublished());
	}

	@Test
	public void aClockCorrectedWhileTorCannotBootstrapClearsTheSkew() {
		Sim m = new Sim();
		m.skew(-7200);
		m.advance(2 * 60_000);
		assertEquals(-7200, m.s.getSkewSeconds());
		m.transportSkew = 0;
		m.advance(TorPublishState.SKEW_RECHECK_MS);
		assertEquals("cleared while Tor is still not active", 0,
				m.s.getSkewSeconds());
		assertFalse(m.s.isPublished());
		m.active();
		m.advance(G - 1);
		assertFalse("Tor then waits a full grace", m.s.isPublished());
		m.advance(1);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void aStaleSkewIsClearedWhenTorBecomesActiveWithAFreshGrace() {
		Sim m = new Sim();
		m.active();
		m.advance(10_000);
		m.skew(3600);
		m.advance(10_000);
		m.drop(false);
		m.advance(5_000);
		m.transportSkew = 0;
		m.active();
		assertEquals(0, m.s.getSkewSeconds());
		assertEquals(m.now + G, m.checkAt);
		m.advance(G - 1);
		assertFalse(m.s.isPublished());
		m.advance(1);
		assertTrue(m.s.isPublished());
	}

	@Test
	public void aPublishProofIsNotOverriddenByAnUnclearedTransportSkew() {
		Sim m = new Sim();
		m.active();
		m.skew(3600);
		m.proof();
		m.advance(10 * 60_000);
		m.wakeCheck();
		assertTrue(m.s.isPublished());
		assertEquals(0, m.s.getSkewSeconds());
	}
}
