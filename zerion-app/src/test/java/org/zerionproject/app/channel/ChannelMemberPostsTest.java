package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelMemberPostsTest {

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long NOW = 1_800_000_000_000L;

	private ChannelCodec codec;

	@Before
	public void setUp() {
		CryptoComponent crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		codec = new ChannelCodec(crypto);
	}

	@Test
	public void namesAreBoundedAndFreeOfHiddenControls() {
		assertTrue(ChannelMemberPosts.validName("Robin"));
		assertFalse(ChannelMemberPosts.validName(""));
		assertFalse(ChannelMemberPosts.validName("   "));
		assertTrue(ChannelMemberPosts.validName(
				"👩‍💻 Robin"));
		assertFalse(ChannelMemberPosts.validName("Robin\u202eevil"));
		assertFalse(ChannelMemberPosts.validName("Robin\nOwner"));
		assertFalse(ChannelMemberPosts.validName("Ro\u0000bin"));
		assertFalse(ChannelMemberPosts.validName("Ro​bin"));
		assertFalse(ChannelMemberPosts.validName("\uD83D"));
		char[] long65 = new char[65];
		Arrays.fill(long65, 'a');
		assertFalse(ChannelMemberPosts.validName(new String(long65)));
		assertTrue(ChannelMemberPosts.validName(new String(long65, 0, 64)));
	}

	@Test
	public void aNameMayNotStackCombiningMarks() {
		assertTrue(ChannelMemberPosts.validName("Zoe\u0301\u0302"));
		assertFalse(ChannelMemberPosts.validName("Zoe\u0301\u0302\u0303"));
		assertEquals("Zoe\u0301\u0302", ChannelMemberPosts.cleanName(
				"Zoe\u0301\u0302\u0303\u0304"));
		assertTrue(ChannelMemberPosts.validName("\u05e8\u05d5\u05d1\u05d9\u05df"));
	}

	@Test
	public void aNameIsCleanedBeforeItIsSigned() {
		assertEquals("Robinevil",
				ChannelMemberPosts.cleanName(" Robin\u202eevil\n"));
		char[] many = new char[70];
		Arrays.fill(many, 'b');
		String cut = ChannelMemberPosts.cleanName(new String(many));
		assertEquals(ChannelConstants.MAX_MEMBER_NAME_CHARS, cut.length());
		StringBuilder pairs = new StringBuilder("a");
		for (int i = 0; i < 40; i++) pairs.append("😀");
		String bounded = ChannelMemberPosts.cleanName(pairs.toString());
		assertTrue(ChannelMemberPosts.validName(bounded));
		assertFalse(Character.isHighSurrogate(
				bounded.charAt(bounded.length() - 1)));
	}

	@Test
	public void aBodyThatLooksLikeAnOwnersMarkIsNotAPost() {
		byte[] id = new byte[32];
		assertFalse(ChannelMemberPosts.validBody(
				ChannelPostMarkers.deletion(id, 3L)));
		assertFalse(ChannelMemberPosts.validBody(
				ChannelPostMarkers.edit(id, 3L, "new words")));
		assertFalse(ChannelMemberPosts.validBody("   "));
		assertFalse(ChannelMemberPosts.validBody(
				" " + ChannelConstants.DELETED_POST_PLACEHOLDER + " "));
		assertFalse(ChannelMemberPosts.validBody(
				"  " + ChannelPostMarkers.deletion(id, 3L)));
		assertTrue(ChannelMemberPosts.validBody("Road is open"));
	}

	@Test
	public void theQuotaCountsHoursAndDaysAndRemembersSubmissions() {
		ChannelMemberPosts.Quota q = ChannelMemberPosts.Quota.parse(null, NOW);
		String stored = null;
		for (int i = 0; i < ChannelConstants.MAX_MEMBER_POSTS_PER_HOUR; i++) {
			assertFalse(q.full());
			stored = q.after(i, 100L + i);
			q = ChannelMemberPosts.Quota.parse(stored, NOW);
		}
		assertTrue("ten in one hour", q.full());
		assertEquals(Long.valueOf(103L), q.acceptedAs(3L));
		assertNull(q.acceptedAs(99L));
		q = ChannelMemberPosts.Quota.parse(stored, NOW + HOUR);
		assertFalse("a new hour", q.full());
		long n = 10L;
		while (!q.full()) {
			stored = q.after(n, 100L + n);
			n++;
			q = ChannelMemberPosts.Quota.parse(stored, NOW + HOUR * (n / 10));
		}
		assertEquals(ChannelConstants.MAX_MEMBER_POSTS_PER_DAY, n);
		assertTrue(ChannelMemberPosts.Quota.parse(stored,
				NOW + 4L * HOUR).full());
	}

	@Test
	public void onlyTheLatestSubmissionsAreRemembered() {
		ChannelMemberPosts.Quota q = ChannelMemberPosts.Quota.parse(null, NOW);
		String stored = null;
		for (int i = 0; i < 100; i++) {
			stored = q.after(i, i);
			q = ChannelMemberPosts.Quota.parse(stored, NOW - 2 * 24 * HOUR);
		}
		q = ChannelMemberPosts.Quota.parse(stored, NOW);
		assertEquals(ChannelMemberPosts.MAX_REMEMBERED_SUBMISSIONS,
				q.accepted.size());
		assertNull(q.acceptedAs(35L));
		assertEquals(Long.valueOf(99L), q.acceptedAs(99L));
	}

	@Test
	public void aDamagedQuotaRecordStartsAgain() {
		ChannelMemberPosts.Quota q =
				ChannelMemberPosts.Quota.parse("x:y:z", NOW);
		assertFalse(q.full());
		assertTrue(q.accepted.isEmpty());
	}

	@Test
	public void theAuthorIsPartOfWhatTheOwnerSigns() {
		ChannelPost base = memberPost("Robin", (byte) 1,
				ChannelConstants.MEMBER_ROLE_SUBSCRIBER);
		byte[] input = codec.signedInputOf(base);
		assertEquals(ChannelPost.FORMAT_V3, input[0]);
		assertFalse(Arrays.equals(input, codec.signedInputOf(
				memberPost("Robim", (byte) 1,
						ChannelConstants.MEMBER_ROLE_SUBSCRIBER))));
		assertFalse(Arrays.equals(input, codec.signedInputOf(
				memberPost("Robin", (byte) 2,
						ChannelConstants.MEMBER_ROLE_SUBSCRIBER))));
		assertFalse(Arrays.equals(input, codec.signedInputOf(
				memberPost("Robin", (byte) 1, 2))));
		assertArrayEquals(input, codec.signedInputOf(memberPost("Robin",
				(byte) 1, ChannelConstants.MEMBER_ROLE_SUBSCRIBER)));
		assertFalse(Arrays.equals(codec.canonicalHashOf(base),
				codec.canonicalHashOf(memberPost("Robim", (byte) 1,
						ChannelConstants.MEMBER_ROLE_SUBSCRIBER))));
	}

	@Test
	public void aMemberPostNeverCarriesTheOwnersMarks() {
		ChannelPost p = memberPost("Robin", (byte) 1,
				ChannelConstants.MEMBER_ROLE_SUBSCRIBER);
		assertTrue(p.isMemberPost());
		assertFalse(p.carriesOwnerMarks());
		assertFalse(p.isEditableAt(NOW));
	}

	@Test
	public void theAuthorCountsTowardsTheBytesAPostHolds() {
		ChannelPost p = memberPost("Robin", (byte) 1,
				ChannelConstants.MEMBER_ROLE_SUBSCRIBER);
		ChannelPost plain = new ChannelPost(new byte[32], 4L, new byte[32],
				NOW, "body",
				Collections.<ChannelPost.ChannelAttachment>emptyList(), 0L,
				new byte[64], false);
		assertEquals(ChannelPostCeilings.storedBytes(plain) + 32 + 1952
						+ "Robin".length() + 1,
				ChannelPostCeilings.storedBytes(p));
	}

	private static ChannelPost memberPost(String name, byte key, int role) {
		byte[] ed = new byte[32];
		Arrays.fill(ed, key);
		byte[] ml = new byte[1952];
		return new ChannelPost(new byte[32], 4L, new byte[32], NOW, "body",
				Collections.<ChannelPost.ChannelAttachment>emptyList(), 0L,
				new byte[64], false, false, new byte[16],
				new ChannelPost.MemberAuthor(ed, ml, name, role));
	}
}
