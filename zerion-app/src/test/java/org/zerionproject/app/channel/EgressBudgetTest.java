package org.zerionproject.app.channel;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * NET-04: the publisher's shared egress budget must bound total bytes served
 * per window across concurrent anonymous readers, reset each window, and still
 * allow one legitimate over-budget object per window.
 */
public class EgressBudgetTest {

	private final AtomicLong now = new AtomicLong(1_000_000L);

	private EgressBudget budget(long window, long max) {
		return new EgressBudget(window, max, now::get);
	}

	@Test
	public void servesUpToTheBudgetThenRejects() {
		EgressBudget b = budget(60_000L, 1000L);
		assertTrue(b.tryCharge(600L));
		assertTrue(b.tryCharge(400L));
		assertFalse("budget for the window must be exhausted",
				b.tryCharge(1L));
	}

	@Test
	public void budgetResetsAfterTheWindow() {
		EgressBudget b = budget(60_000L, 1000L);
		assertTrue(b.tryCharge(1000L));
		assertFalse(b.tryCharge(1L));
		now.addAndGet(60_001L);
		assertTrue("a fresh window must have full budget",
				b.tryCharge(1000L));
	}

	@Test
	public void oneOversizeObjectIsAllowedPerWindow() {
		EgressBudget b = budget(60_000L, 1000L);
		assertTrue("a single object larger than the window budget still serves",
				b.tryCharge(5000L));
		assertFalse("but nothing more until the window rolls over",
				b.tryCharge(1L));
		now.addAndGet(60_001L);
		assertTrue(b.tryCharge(5000L));
	}

	@Test
	public void manyReadersShareOneBudget() {
		EgressBudget b = budget(60_000L, 10_000L);
		int served = 0;
		for (int i = 0; i < 1000; i++) {
			if (b.tryCharge(1000L)) served++;
		}
		assertTrue("total served must be bounded by the window budget",
				served <= 10);
	}
}
