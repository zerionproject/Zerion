package com.professor.zerion.android.grouptr;

import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getLocalAuthor;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupSenderWordingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;
	private static final Pattern KEY_FRAGMENT =
			Pattern.compile("[0-9a-f]{4}( [0-9a-f]{4}){3}");

	static final List<Runnable> io = new ArrayList<>();
	static GroupTrManager manager;
	static LocalAuthor me;

	public static class ProbeConversation extends GroupTrConversationActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			IdentityManager identity = mock(IdentityManager.class);
			try {
				when(identity.getLocalAuthor()).thenReturn(me);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			identityManager = identity;
			contactManager = mock(ContactManager.class);
			ioExecutor = io::add;
		}
	}

	private final byte[] groupId = new byte[32];
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] samKey = key((byte) 3);
	private final byte[] otherSamKey = key((byte) 4);
	private final byte[] strangerKey = key((byte) 6);
	private final byte[] samMlDsa = key((byte) 7);
	private List<GroupTrMember> members;
	private final long now = System.currentTimeMillis();
	private ProbeConversation activity;

	@Before
	public void setUp() throws Exception {
		io.clear();
		ShadowDialog.reset();
		me = getLocalAuthor();
		manager = mock(GroupTrManager.class);
		members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "MotoDev", now - 30 * DAY,
				0L, MemberRole.CREATOR));
		members.add(new GroupTrMember(samKey, "Sam", now - 20 * DAY, 1L,
				MemberRole.MEMBER, samMlDsa));
		members.add(new GroupTrMember(otherSamKey, "Sam", now - 10 * DAY,
				1L));
		members.add(new GroupTrMember(me.getPublicKey().getEncoded(), "Me",
				now - 5 * DAY, 1L));
		group();
		when(manager.getRecentPosts(any()))
				.thenReturn(Collections.<GroupTrPost>emptyList());
	}

	private void group() throws Exception {
		GroupTrState state = new GroupTrState(groupId, "Family", new byte[32],
				creatorKey, "MotoDev", 0L, 1L, false, members);
		when(manager.getGroup(any())).thenReturn(state);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}

	private GroupTrPost post(byte[] sender, String alias, String text,
			long at) {
		return new GroupTrPost(groupId, sender, alias,
				GroupTrBody.encodeText(text), at, 1L, false);
	}

	private void posts(GroupTrPost... posts) throws Exception {
		when(manager.getRecentPosts(any())).thenReturn(Arrays.asList(posts));
	}

	private ProbeConversation open() {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		activity = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup().get();
		runIo();
		return activity;
	}

	private GroupTrPostAdapter.PostHolder bound(int position) {
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		GroupTrPostAdapter adapter = (GroupTrPostAdapter) list.getAdapter();
		assertNotNull(adapter);
		assertTrue("no post at " + position,
				adapter.getItemCount() > position);
		GroupTrPostAdapter.PostHolder h = adapter.onCreateViewHolder(list,
				adapter.getItemViewType(position));
		adapter.onBindViewHolder(h, position);
		return h;
	}

	private String text(GroupTrPostAdapter.PostHolder h, int id) {
		TextView view = h.itemView.findViewById(id);
		assertNotNull(view);
		return view.getText().toString();
	}

	private String sender(int position) {
		return text(bound(position), R.id.senderName);
	}

	@Test
	public void aMemberPostingUnderAnotherNameIsNamedWithoutAKeyFragment()
			throws Exception {
		posts(post(creatorKey, "CbvMoto", "Dinner at seven", now - 60_000L));
		open();
		String label = sender(0);
		assertEquals("MotoDev (posting as \"CbvMoto\")", label);
		assertFalse(label, KEY_FRAGMENT.matcher(label).find());
	}

	@Test
	public void twoMembersSharingANameAreToldApartByTheDayTheyJoined()
			throws Exception {
		posts(post(samKey, "Sam", "Hi", now - 120_000L),
				post(otherSamKey, "Sam", "Hello", now - 60_000L));
		open();
		String first = sender(0);
		String second = sender(1);
		assertTrue(first, first.startsWith("Sam (joined "));
		assertTrue(second, second.startsWith("Sam (joined "));
		assertNotEquals(first, second);
		assertFalse(first, KEY_FRAGMENT.matcher(first).find());
	}

	@Test
	public void membersLearnedFromASnapshotNeverShowJanuary1970()
			throws Exception {
		members.set(1, new GroupTrMember(samKey, "Sam", 0L, 1L));
		members.set(2, new GroupTrMember(otherSamKey, "Sam", 0L, 1L));
		group();
		posts(post(samKey, "Sam", "Hi", now - 9 * DAY),
				post(otherSamKey, "Sam", "Hello", now - 2 * DAY),
				post(samKey, "Sam", "Again", now - HOUR));
		open();
		String first = sender(0);
		String second = sender(1);
		assertTrue(first, first.startsWith("Sam (first wrote "));
		assertTrue(second, second.startsWith("Sam (first wrote "));
		assertNotEquals(first, second);
		assertEquals(first, sender(2));
		assertFalse(first, first.contains("1970"));
		assertFalse(second, second.contains("1970"));
	}

	@Test
	public void aMemberWithAUniqueNameIsShownByNameAlone() throws Exception {
		posts(post(creatorKey, "MotoDev", "Dinner at seven", now - 60_000L));
		open();
		assertEquals("MotoDev", sender(0));
	}

	@Test
	public void tappingTheSenderOpensTheSafetyCode() throws Exception {
		posts(post(creatorKey, "MotoDev", "Dinner at seven", now - 60_000L));
		open();
		GroupTrPostAdapter.PostHolder h = bound(0);
		h.itemView.findViewById(R.id.senderName).performClick();
		idle();
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("no safety code dialog", dialog);
		TextView title = dialog.findViewById(
				androidx.appcompat.R.id.alertTitle);
		assertNotNull(title);
		assertEquals(activity.getString(R.string.grouptr_member_safety_code),
				title.getText().toString());
		TextView message = ((AlertDialog) dialog)
				.findViewById(android.R.id.message);
		assertNotNull(message);
		String shown = message.getText().toString();
		assertTrue(shown, shown.contains("MotoDev"));
		assertFalse(shown, shown.toLowerCase().contains("signing key"));
	}

	private String safetyCodeMessage(int position) {
		GroupTrPostAdapter.PostHolder h = bound(position);
		h.itemView.findViewById(R.id.senderName).performClick();
		idle();
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("no safety code dialog", dialog);
		TextView message = ((AlertDialog) dialog)
				.findViewById(android.R.id.message);
		assertNotNull(message);
		return message.getText().toString();
	}

	@Test
	public void theSafetyCodeOfAStrangerSpeaksOfThisPerson()
			throws Exception {
		posts(post(strangerKey, "Zed", "Who am I", now - 60_000L));
		open();
		String shown = safetyCodeMessage(0);
		assertTrue(shown, shown.contains("If this person sees"));
		assertFalse(shown, shown.contains("not a name you know"));
		assertFalse(shown, shown.contains("\""));
	}

	@Test
	public void theSafetyCodeUsesTheMembersOwnSecondKey() throws Exception {
		posts(post(samKey, "Sam", "Hi", now - 60_000L),
				post(otherSamKey, "Sam", "Hello", now - 30_000L));
		open();
		String withKey = safetyCodeMessage(0);
		assertTrue(withKey, withKey.contains("Version 2"));
		assertTrue(withKey, withKey.contains("Version 1"));
		String withoutKey = safetyCodeMessage(1);
		assertFalse(withoutKey, withoutKey.contains("Version 2"));
		assertTrue(withoutKey, withoutKey.contains("Version 1"));
	}

	@Test
	public void aPostFromTwoDaysAgoShowsOneTimeNotTwo() throws Exception {
		long at = now - 2 * DAY;
		posts(post(creatorKey, "MotoDev", "Dinner at seven", at));
		open();
		String shown = text(bound(0), R.id.postTime);
		assertEquals(com.professor.zerion.android.util.UiUtils
				.formatDate(activity, at), shown);
		Pattern clock = Pattern.compile("\\d{1,2}:\\d{2}");
		java.util.regex.Matcher m = clock.matcher(shown);
		int clocks = 0;
		while (m.find()) clocks++;
		assertTrue(shown, clocks <= 1);
	}

	@Test
	public void aPostFromTodayShowsTheTimeOfDayLikeAChat() throws Exception {
		long at = now - 60_000L;
		posts(post(creatorKey, "MotoDev", "Dinner at seven", at));
		open();
		assertEquals(com.professor.zerion.android.util.UiUtils
				.formatDate(activity, at), text(bound(0), R.id.postTime));
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void runIo() {
		idle();
		for (int round = 0; round < 20 && !io.isEmpty(); round++) {
			List<Runnable> pending = new ArrayList<>(io);
			io.clear();
			for (Runnable r : pending) r.run();
			idle();
		}
		idle();
	}
}
