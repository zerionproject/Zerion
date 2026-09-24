package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.system.Clock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The send timestamp placed in an offline envelope is in milliseconds, the
 * unit the delivery layer compares against its own clock; a value in
 * seconds would make every envelope look expired by decades and be refused
 * after the full open.
 */
public class MeshManagerTimestampTest {

	@Test
	public void theEnvelopeTimestampIsTheClockInMilliseconds() {
		long now = 1_790_000_000_123L;
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return now;
			}

			@Override
			public void sleep(long milliseconds) {
			}
		};
		assertEquals(now, MeshManager.sendTimestampMs(clock));
	}
}
