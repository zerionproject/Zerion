package com.professor.zerion.android.grouptr;

import org.junit.Test;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.util.StringUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class GroupTrSenderLabelTest {

	private static final String CHOSEN = "%1$s (posting as \"%2$s\")";
	private static final String UNVERIFIED = "\"%1$s\" (not a name you know)";
	private static final String INCOGNITO = "\"%1$s\" (incognito)";
	private static final String JOINED = "%1$s (joined %2$s)";
	private static final String WROTE = "%1$s (first wrote %2$s)";
	private static final Map<String, Long> NO_POSTS =
			java.util.Collections.emptyMap();

	@Test
	public void theKnownNameLeadsAndAChosenNameIsMarked() {
		assertEquals("Mallory (posting as \"Alice\")",
				GroupTrSenderLabel.label("Mallory", "Alice", CHOSEN,
						UNVERIFIED));
		assertEquals("Alice", GroupTrSenderLabel.label("Alice", "Alice",
				CHOSEN, UNVERIFIED));
		assertEquals("Alice", GroupTrSenderLabel.label("Alice", "", CHOSEN,
				UNVERIFIED));
	}

	@Test
	public void aSenderKnownByNoNameIsMarkedUnverified() {
		assertEquals("\"Alice\" (not a name you know)",
				GroupTrSenderLabel.label(null, "Alice", CHOSEN, UNVERIFIED));
	}

	@Test
	public void anIncognitoPosterIsShownOnlyByItsGroupName() {
		assertEquals("\"Amber Falcon 27\" (incognito)",
				GroupTrSenderLabel.label("Mallory", "Amber Falcon 27", CHOSEN,
						UNVERIFIED, INCOGNITO));
		assertEquals("\"Amber Falcon 27\" (incognito)",
				GroupTrSenderLabel.label(null, "Amber Falcon 27", CHOSEN,
						UNVERIFIED, INCOGNITO));
	}

	@Test
	public void aChosenNameThatIsNotAnIncognitoNameKeepsTheKnownName() {
		assertEquals("Mallory (posting as \"Alice\")",
				GroupTrSenderLabel.label("Mallory", "Alice", CHOSEN,
						UNVERIFIED, INCOGNITO));
		assertEquals("Mallory (posting as \"amber falcon 27\")",
				GroupTrSenderLabel.label("Mallory", "amber falcon 27", CHOSEN,
						UNVERIFIED, INCOGNITO));
	}

	@Test
	public void noLabelCarriesAKeyFragment() {
		String[] labels = {
				GroupTrSenderLabel.label("Mallory", "Alice", CHOSEN,
						UNVERIFIED, INCOGNITO),
				GroupTrSenderLabel.label(null, "Alice", CHOSEN, UNVERIFIED,
						INCOGNITO),
				GroupTrSenderLabel.label(null, "Amber Falcon 27", CHOSEN,
						UNVERIFIED, INCOGNITO)};
		for (String label : labels) {
			assertFalse(label, label.contains("·"));
			assertFalse(label, label.matches(".*[0-9a-f]{4} [0-9a-f]{4}.*"));
		}
	}

	private static GroupTrMember member(byte fill, String name, long joined) {
		byte[] key = new byte[32];
		Arrays.fill(key, fill);
		return new GroupTrMember(key, name, joined, 0L, MemberRole.MEMBER);
	}

	private static String hex(GroupTrMember m) {
		return StringUtils.toHexString(m.getPubKey());
	}

	@Test
	public void twoMembersWithTheSameNameAreToldApartByTheDayTheyJoined() {
		GroupTrMember first = member((byte) 1, "Sam", 1_000L);
		GroupTrMember second = member((byte) 2, "Sam", 2_000L);
		GroupTrMember other = member((byte) 3, "Dana", 3_000L);
		Map<String, String> names = new HashMap<>();
		names.put(hex(first), "Sam");
		names.put(hex(second), "Sam");
		names.put(hex(other), "Dana");
		names.put("ff", "Sam");
		List<GroupTrMember> members = Arrays.asList(first, second, other);
		Map<String, String> shown = GroupTrSenderLabel.tellApart(names,
				members, NO_POSTS, JOINED, WROTE, at -> "day " + at);
		assertEquals("Sam (joined day 1000)", shown.get(hex(first)));
		assertEquals("Sam (joined day 2000)", shown.get(hex(second)));
		assertEquals("Dana", shown.get(hex(other)));
		assertEquals("a contact outside the group is left alone", "Sam",
				shown.get("ff"));
	}

	@Test
	public void aNameSharedOnlyWithSomeoneOutsideTheGroupStaysPlain() {
		GroupTrMember sam = member((byte) 1, "Sam", 1_000L);
		Map<String, String> names = new HashMap<>();
		names.put(hex(sam), "Sam");
		names.put("ff", "Sam");
		Map<String, String> shown = GroupTrSenderLabel.tellApart(names,
				Arrays.asList(sam), NO_POSTS, JOINED, WROTE, at -> "day " + at);
		assertEquals("Sam", shown.get(hex(sam)));
	}

	@Test
	public void anUnknownJoinDayFallsBackToTheFirstPostSeenHere() {
		GroupTrMember first = member((byte) 1, "Sam", 0L);
		GroupTrMember second = member((byte) 2, "Sam", 0L);
		Map<String, String> names = new HashMap<>();
		names.put(hex(first), "Sam");
		names.put(hex(second), "Sam");
		Map<String, Long> posts = new HashMap<>();
		posts.put(hex(first), 5_000L);
		posts.put(hex(second), 9_000L);
		Map<String, String> shown = GroupTrSenderLabel.tellApart(names,
				Arrays.asList(first, second), posts, JOINED, WROTE,
				at -> "day " + at);
		assertEquals("Sam (first wrote day 5000)", shown.get(hex(first)));
		assertEquals("Sam (first wrote day 9000)", shown.get(hex(second)));
		for (String label : shown.values()) {
			assertFalse(label, label.contains("day 0"));
		}
	}

	@Test
	public void aMemberWithNeitherJoinDayNorPostStaysPlain() {
		GroupTrMember known = member((byte) 1, "Sam", 7_000L);
		GroupTrMember silent = member((byte) 2, "Sam", 0L);
		Map<String, String> names = new HashMap<>();
		names.put(hex(known), "Sam");
		names.put(hex(silent), "Sam");
		Map<String, String> shown = GroupTrSenderLabel.tellApart(names,
				Arrays.asList(known, silent), NO_POSTS, JOINED, WROTE,
				at -> "day " + at);
		assertEquals("Sam (joined day 7000)", shown.get(hex(known)));
		assertEquals("Sam", shown.get(hex(silent)));
	}

	@Test
	public void theFirstPostPerSenderIsTheEarliestOne() {
		byte[] key = new byte[32];
		Arrays.fill(key, (byte) 4);
		Map<String, Long> first = GroupTrSenderLabel.firstPosts(Arrays.asList(
				new org.zerionproject.app.api.grouptr.GroupTrPost(new byte[32],
						key, "Sam", new byte[0], 9_000L, 1L, false),
				new org.zerionproject.app.api.grouptr.GroupTrPost(new byte[32],
						key, "Sam", new byte[0], 3_000L, 1L, false)));
		assertEquals(Long.valueOf(3_000L),
				first.get(StringUtils.toHexString(key)));
	}
}
