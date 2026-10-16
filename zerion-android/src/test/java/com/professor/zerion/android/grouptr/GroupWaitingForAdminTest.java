package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupWaitingForAdminTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long SETTLE_MS = 15_000;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);

	@After
	public void tearDown() throws Exception {
		if (GroupConversationReloadTest.ProbeConversation.io != null) {
			GroupConversationReloadTest.ProbeConversation.io.pool
					.shutdownNow();
			GroupConversationReloadTest.ProbeConversation.io.pool
					.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aJoiningMemberIsToldItWaitsForTheCreatorAndCannotPost()
			throws Exception {
		GroupTrManager m = mock(GroupTrManager.class);
		when(m.getGroup(any())).thenReturn(state());
		when(m.getRecentPosts(any())).thenReturn(new ArrayList<>());
		when(m.getMembersOutOfReach(any())).thenReturn(new ArrayList<>());
		when(m.isWaitingForAdmin(any())).thenReturn(true);

		GroupConversationReloadTest.ProbeConversation a = open(m);

		TextView banner = a.findViewById(R.id.removedBanner);
		assertEquals(View.VISIBLE, banner.getVisibility());
		assertEquals(a.getString(R.string.grouptr_waiting_for_admin, "Carol"),
				banner.getText().toString());
		assertEquals(View.GONE,
				a.findViewById(R.id.composerRow).getVisibility());
	}

	@Test
	public void aDissolvedGroupSaysSoAndOffersNoComposer() throws Exception {
		GroupTrManager m = mock(GroupTrManager.class);
		when(m.getGroup(any())).thenReturn(state(true));
		when(m.getRecentPosts(any())).thenReturn(new ArrayList<>());
		when(m.getMembersOutOfReach(any())).thenReturn(new ArrayList<>());
		when(m.isWaitingForAdmin(any())).thenReturn(false);

		GroupConversationReloadTest.ProbeConversation a = open(m);

		TextView banner = a.findViewById(R.id.removedBanner);
		assertEquals(View.VISIBLE, banner.getVisibility());
		assertEquals(a.getString(R.string.groups_group_is_dissolved),
				banner.getText().toString());
		assertEquals(View.GONE,
				a.findViewById(R.id.composerRow).getVisibility());
	}

	private GroupConversationReloadTest.ProbeConversation open(
			GroupTrManager m) throws Exception {
		GroupConversationReloadTest.ProbeConversation.manager = m;
		GroupConversationReloadTest.ProbeConversation.io =
				new GroupConversationReloadTest.TrackedExecutor();
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				GroupConversationReloadTest.ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID,
				StringUtils.toHexString(groupId));
		GroupConversationReloadTest.ProbeConversation a = Robolectric
				.buildActivity(GroupConversationReloadTest.ProbeConversation
						.class, i).setup().get();
		settle();
		return a;
	}

	private static void settle() throws Exception {
		long deadline = System.currentTimeMillis() + SETTLE_MS;
		while (true) {
			shadowOf(Looper.getMainLooper()).idle();
			if (GroupConversationReloadTest.ProbeConversation.io.pending
					.get() == 0) {
				shadowOf(Looper.getMainLooper()).idle();
				if (GroupConversationReloadTest.ProbeConversation.io.pending
						.get() == 0) {
					return;
				}
			}
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the screen kept loading");
			}
			Thread.sleep(10);
		}
	}

	private GroupTrState state() {
		return state(false);
	}

	private GroupTrState state(boolean dissolved) {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Carol", 0L, 0L,
				MemberRole.CREATOR));
		return new GroupTrState(groupId, "Club", new byte[32], creatorKey,
				"Carol", 0L, 0L, dissolved, members);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
