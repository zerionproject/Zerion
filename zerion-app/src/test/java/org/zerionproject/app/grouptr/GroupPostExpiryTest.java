package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupPostExpiryTest {

	private static final long MINUTE = 60_000L;

	@Test
	public void aPostExpiresWhenItsTimerHasRunOutAndNotBefore() {
		GroupTrPost p = post(1_000L, MINUTE);
		assertFalse(p.isExpiredAt(1_000L + MINUTE - 1));
		assertTrue(p.isExpiredAt(1_000L + MINUTE));
		assertEquals(1_000L + MINUTE, p.getExpiryTime());
	}

	@Test
	public void aPostWithoutATimerNeverExpires() {
		GroupTrPost p = post(1_000L, 0L);
		assertEquals(Long.MAX_VALUE, p.getExpiryTime());
		assertFalse(p.isExpiredAt(Long.MAX_VALUE - 1));
	}

	@Test
	public void sendTimesNearTheEndsOfTheRangeDoNotWrap() {
		long now = 1_700_000_000_000L;
		GroupTrPost early = post(Long.MIN_VALUE + 10, MINUTE);
		assertTrue(early.isExpiredAt(now));
		assertFalse(early.isExpiredAt(Long.MIN_VALUE + 10 + MINUTE - 1));
		GroupTrPost late = post(Long.MAX_VALUE - 10, MINUTE);
		assertEquals(Long.MAX_VALUE, late.getExpiryTime());
		assertFalse(late.isExpiredAt(now));
	}

	private static GroupTrPost post(long timestamp, long timer) {
		return new GroupTrPost(new byte[32], new byte[32], "Name",
				new byte[1], timestamp, 1L, false, timer);
	}
}
