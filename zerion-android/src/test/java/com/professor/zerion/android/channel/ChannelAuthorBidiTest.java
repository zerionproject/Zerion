package com.professor.zerion.android.channel;

import org.junit.Test;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.core.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ChannelAuthorBidiTest {

	private static final String UNNAMED = "Person without a name";
	private static final String WROTE = "%1$s (first wrote %2$s)";

	private static final ChannelAuthorLabels.DayFormatter DAYS =
			new ChannelAuthorLabels.DayFormatter() {
				@Override
				public String day(long hourMs) {
					return "d";
				}

				@Override
				public String dayAndHour(long hourMs) {
					return "dh";
				}
			};

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}

	@Test
	public void aCommentersNameLosesItsDirectionControls() {
		ChannelComment c = new ChannelComment(4L, 1L, "hi",
				"\u2066Ann\u202E", key((byte) 1), new byte[0], 1L);
		Map<String, String> labels = ChannelAuthorLabels.of(
				Collections.singletonList(c), UNNAMED, WROTE, DAYS);
		assertEquals("Ann",
				labels.get(StringUtils.toHexString(key((byte) 1))));
		assertEquals("Ann", ChannelAuthorLabels.label(
				Collections.emptyMap(), c, UNNAMED));
	}

	@Test
	public void aNameMadeOnlyOfControlsCountsAsUnnamed() {
		ChannelComment c = new ChannelComment(4L, 1L, "hi", "\u202E\u2066",
				key((byte) 2), new byte[0], 1L);
		assertEquals(UNNAMED, ChannelAuthorLabels.label(
				Collections.emptyMap(), c, UNNAMED));
	}
}
