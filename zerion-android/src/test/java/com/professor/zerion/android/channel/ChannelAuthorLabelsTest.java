package com.professor.zerion.android.channel;

import org.junit.Test;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.core.util.StringUtils;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ChannelAuthorLabelsTest {

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;
	private static final String UNNAMED = "Person without a name";
	private static final String WROTE = "%1$s (first wrote %2$s)";

	private static final ChannelAuthorLabels.DayFormatter DAYS =
			new ChannelAuthorLabels.DayFormatter() {
				@Override
				public String day(long hourMs) {
					return "day " + hourMs / DAY;
				}

				@Override
				public String dayAndHour(long hourMs) {
					return "day " + hourMs / DAY + " at " + hourMs % DAY / HOUR;
				}
			};

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}

	private static ChannelComment comment(long id, String name, byte[] key,
			long hour) {
		return new ChannelComment(4L, id, "hi", name, key, new byte[0], hour);
	}

	private static String hex(byte[] key) {
		return StringUtils.toHexString(key);
	}

	@Test
	public void aUniqueNameIsShownAlone() {
		byte[] alice = key((byte) 1);
		Map<String, String> labels = ChannelAuthorLabels.of(Arrays.asList(
				comment(1L, "Alice", alice, HOUR),
				comment(2L, "Alice", alice, 2 * HOUR)), UNNAMED, WROTE, DAYS);
		assertEquals("Alice", labels.get(hex(alice)));
		assertFalse(labels.get(hex(alice)).matches(".*[0-9a-f]{16}.*"));
	}

	@Test
	public void twoPeopleWithTheSameNameAreToldApartByTheirFirstComment() {
		byte[] alice = key((byte) 1);
		byte[] otherAlice = key((byte) 2);
		Map<String, String> labels = ChannelAuthorLabels.of(Arrays.asList(
				comment(1L, "Alice", alice, 5 * DAY),
				comment(2L, "Alice", otherAlice, 30 * DAY),
				comment(3L, "Alice", alice, 40 * DAY)), UNNAMED, WROTE, DAYS);
		assertEquals("Alice (first wrote day 5)", labels.get(hex(alice)));
		assertEquals("Alice (first wrote day 30)",
				labels.get(hex(otherAlice)));
	}

	@Test
	public void twoPeopleWhoFirstWroteOnTheSameDayGetTheHourAsWell() {
		byte[] alice = key((byte) 1);
		byte[] otherAlice = key((byte) 2);
		Map<String, String> labels = ChannelAuthorLabels.of(Arrays.asList(
				comment(1L, "Alice", alice, 5 * DAY + 9 * HOUR),
				comment(2L, "Alice", otherAlice, 5 * DAY + 14 * HOUR)),
				UNNAMED, WROTE, DAYS);
		assertEquals("Alice (first wrote day 5 at 9)", labels.get(hex(alice)));
		assertEquals("Alice (first wrote day 5 at 14)",
				labels.get(hex(otherAlice)));
	}

	@Test
	public void twoPeopleWhoFirstWroteInTheSameHourKeepTheSameLabel() {
		byte[] alice = key((byte) 1);
		byte[] otherAlice = key((byte) 2);
		Map<String, String> labels = ChannelAuthorLabels.of(Arrays.asList(
				comment(1L, "Alice", alice, 5 * DAY + 9 * HOUR),
				comment(2L, "Alice", otherAlice, 5 * DAY + 9 * HOUR)),
				UNNAMED, WROTE, DAYS);
		assertEquals("Alice (first wrote day 5 at 9)", labels.get(hex(alice)));
		assertEquals(labels.get(hex(alice)), labels.get(hex(otherAlice)));
	}

	@Test
	public void aPersonWithoutANameIsCalledThat() {
		byte[] nobody = key((byte) 9);
		ChannelComment c = comment(1L, "  ", nobody, HOUR);
		Map<String, String> labels = ChannelAuthorLabels.of(
				Arrays.asList(c), UNNAMED, WROTE, DAYS);
		assertEquals(UNNAMED, labels.get(hex(nobody)));
		assertEquals(UNNAMED, ChannelAuthorLabels.label(labels, c, UNNAMED));
	}

	@Test
	public void twoPeopleWithoutANameAreToldApartToo() {
		byte[] a = key((byte) 1);
		byte[] b = key((byte) 2);
		Map<String, String> labels = ChannelAuthorLabels.of(Arrays.asList(
				comment(1L, "", a, DAY), comment(2L, "", b, 2 * DAY)),
				UNNAMED, WROTE, DAYS);
		assertEquals("Person without a name (first wrote day 1)",
				labels.get(hex(a)));
		assertEquals("Person without a name (first wrote day 2)",
				labels.get(hex(b)));
	}

	@Test
	public void aCommentOutsideTheMapFallsBackToItsOwnName() {
		ChannelComment c = comment(1L, "Zed", key((byte) 3), HOUR);
		assertEquals("Zed", ChannelAuthorLabels.label(
				java.util.Collections.<String, String>emptyMap(), c,
				UNNAMED));
	}
}
