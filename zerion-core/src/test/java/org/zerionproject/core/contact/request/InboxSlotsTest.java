package org.zerionproject.core.contact.request;

import org.junit.Test;
import org.zerionproject.core.test.BrambleTestCase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class InboxSlotsTest extends BrambleTestCase {

	private final InboxSlots slots = new InboxSlots();

	@Test
	public void aNewUnsolvedConnectionReplacesTheOldOne() {
		Object a = new Object(), b = new Object();
		assertNull(slots.connected(a, 0));
		assertSame(a, slots.connected(b, 1));
		assertFalse(slots.solutionReceived(a));
		assertTrue(slots.solutionReceived(b));
	}

	@Test
	public void anUnsolvedConnectionIsHeldAtMostFiveSeconds() {
		Object a = new Object();
		slots.connected(a, 1000);
		assertNull(slots.expireUnsolved(5999));
		assertSame(a, slots.expireUnsolved(6000));
		assertFalse(slots.hasUnsolved());
	}

	@Test
	public void aConnectionBeingValidatedIsNeverReplacedAsUnsolved() {
		Object a = new Object(), b = new Object();
		slots.connected(a, 0);
		assertTrue(slots.solutionReceived(a));
		assertNull("a validating connection is not the unsolved one",
				slots.connected(b, 1));
		assertSame(b, slots.expireUnsolved(10_000));
	}

	@Test
	public void solvedSlotsAreNeverTakenByUnsolvedConnections() {
		Object[] solved = {new Object(), new Object(), new Object()};
		for (Object s : solved) {
			slots.connected(s, 0);
			assertTrue(slots.solutionReceived(s));
			assertTrue(slots.acquireSolved(s));
		}
		for (int i = 0; i < 100; i++) slots.connected(new Object(), i);
		assertEquals(3, slots.solvedCount());
		Object late = new Object();
		slots.connected(late, 200);
		assertTrue(slots.solutionReceived(late));
		assertFalse("a fourth solved connection finds the slots busy",
				slots.acquireSolved(late));
		slots.closed(solved[0]);
		assertTrue(slots.acquireSolved(late));
	}
}
