package org.zerionproject.sync;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZppConnectionRunnerJitterTest {

	@Test
	public void testJitterStaysInBoundsAndAveragesToBase() {
		long base = 750, jitter = 250;
		Random r = new Random(42);
		long sum = 0;
		int n = 100_000;
		long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
		for (int i = 0; i < n; i++) {
			long v = ZppConnectionRunnerImpl.computeInterval(base, jitter, r);
			assertTrue("below lower bound: " + v, v >= base - jitter);
			assertTrue("above upper bound: " + v, v <= base + jitter);
			min = Math.min(min, v);
			max = Math.max(max, v);
			sum += v;
		}
		double avg = sum / (double) n;
		assertTrue("average drifted from base: " + avg,
				Math.abs(avg - base) < 5);
		assertTrue(min <= base - jitter + 5);
		assertTrue(max >= base + jitter - 5);
	}

	@Test
	public void testNeverBurstsAndZeroJitterIsConstant() {
		Random r = new Random(1);
		for (int i = 0; i < 1000; i++) {
			assertTrue(ZppConnectionRunnerImpl.computeInterval(1, 100, r) >= 1);
		}
		assertEquals(750, ZppConnectionRunnerImpl.computeInterval(750, 0, r));
	}
}
