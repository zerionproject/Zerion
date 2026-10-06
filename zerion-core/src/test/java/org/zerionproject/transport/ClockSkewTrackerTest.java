package org.zerionproject.transport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClockSkewTrackerTest {

	@Test
	public void withoutAReportThereIsNoSkew() {
		assertEquals(0, new ClockSkewTracker().current());
	}

	@Test
	public void aReportAppliesUntilCleared() {
		ClockSkewTracker t = new ClockSkewTracker();
		t.onSkew(3600);
		assertEquals(3600, t.current());
		t.clear();
		assertEquals(0, t.current());
	}

	@Test
	public void aNewReportReplacesTheOldOne() {
		ClockSkewTracker t = new ClockSkewTracker();
		t.onSkew(3600);
		t.onSkew(-7200);
		assertEquals(-7200, t.current());
	}
}
