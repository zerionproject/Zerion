package com.professor.zerion.android.navdrawer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublishGraceTest {

	private static final long GRACE = 60_000;

	@Test
	public void theFirstActiveTransitionArmsTheFullGrace() {
		PublishGrace g = new PublishGrace(GRACE);
		assertFalse(g.isArmed());
		assertEquals(GRACE, g.onActive(1_000));
		assertTrue(g.isArmed());
	}

	@Test
	public void aLaterActiveTransitionDoesNotRestartTheWait() {
		PublishGrace g = new PublishGrace(GRACE);
		g.onActive(1_000);
		assertEquals(31_000, g.onActive(30_000));
		assertEquals(11_000, g.onActive(50_000));
	}

	@Test
	public void flappingFasterThanTheGraceStillReachesTheDeadline() {
		PublishGrace g = new PublishGrace(GRACE);
		long now = 0;
		long delay = GRACE;
		for (int i = 0; i < 100; i++) {
			delay = g.onActive(now);
			if (delay == 0) break;
			now += 20_000;
		}
		assertEquals(0, delay);
		assertTrue(now <= GRACE + 20_000);
	}

	@Test
	public void anActiveTransitionAfterTheDeadlineAssumesPublishedAtOnce() {
		PublishGrace g = new PublishGrace(GRACE);
		g.onActive(0);
		assertEquals(0, g.onActive(GRACE + 5_000));
	}

	@Test
	public void resetMakesTheNextActiveTransitionWaitAfresh() {
		PublishGrace g = new PublishGrace(GRACE);
		g.onActive(0);
		g.onActive(70_000);
		g.reset();
		assertFalse(g.isArmed());
		assertEquals(GRACE, g.onActive(100_000));
	}
}
