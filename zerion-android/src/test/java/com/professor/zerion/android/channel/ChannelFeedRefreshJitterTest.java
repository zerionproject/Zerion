package com.professor.zerion.android.channel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelFeedRefreshJitterTest {

	@Test
	public void theDelayVariesBetweenHalfAndOneAndAHalfTimesTheBase() {
		assertEquals(6_000L,
				ChannelFeedActivity.jitteredRefreshDelay(12_000L, 0.0));
		assertEquals(12_000L,
				ChannelFeedActivity.jitteredRefreshDelay(12_000L, 0.5));
		assertTrue(ChannelFeedActivity.jitteredRefreshDelay(12_000L,
				Math.nextDown(1.0)) <= 18_000L);
		Random r = new Random(7);
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			long d = ChannelFeedActivity.jitteredRefreshDelay(3_500L,
					r.nextDouble());
			assertTrue(d >= 1_750L && d <= 5_250L);
			seen.add(d);
		}
		assertTrue(seen.size() > 100);
	}

	@Test
	public void everyScheduledRefreshIsJittered() throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/channel/"
						+ "ChannelFeedActivity.java")), StandardCharsets.UTF_8);
		assertTrue(src.contains(
				"refreshHandler.postDelayed(this, nextRefreshDelay(delay));"));
		assertTrue(src.contains("refreshHandler.postDelayed(refreshTick,\n"
				+ "\t\t\t\tnextRefreshDelay(FOREGROUND_REFRESH_ACTIVE_MS));"));
		assertFalse(src.contains("postDelayed(this, delay)"));
		assertFalse(src.contains("refreshTick,\n\t\t\t\tFOREGROUND_REFRESH"));
	}
}
