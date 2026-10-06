package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupConversationEventsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {

		static GroupTrManager manager;
		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = mock(IdentityManager.class, RETURNS_DEEP_STUBS);
			ioExecutor = io::add;
		}
	}

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] memberKey = key((byte) 3);
	private final byte[] strangerKey = key((byte) 4);
	private byte[] groupId;
	private GroupTrManager manager;

	@Before
	public void setUp() throws Exception {
		groupId = StringUtils.fromHexString(GROUP_HEX);
		ProbeConversation.io.clear();
		manager = mock(GroupTrManager.class);
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Creator", 0L, 0L,
				MemberRole.CREATOR));
		members.add(new GroupTrMember(memberKey, "Member", 0L, 1L));
		members.add(new GroupTrMember(strangerKey, "Stranger", 0L, 1L));
		when(manager.getGroup(any())).thenReturn(new GroupTrState(groupId,
				"Group", new byte[32], creatorKey, "Creator", 0L, 1L, false,
				members));
		when(manager.getMembersOutOfReach(any())).thenReturn(
				Collections.singletonList(members.get(2)));
		ProbeConversation.manager = manager;
	}

	@Test
	public void anAcceptedPostAnnouncedByTheManagerIsShown()
			throws Exception {
		ProbeConversation a = open();
		clearInvocations(manager);

		a.eventOccurred(new GroupTrPostAcceptedEvent(groupId, false));
		runIo();

		verify(manager, atLeast(1)).getRecentPosts(any());
	}

	@Test
	public void aRawPostEventShowsOnlyWhatTheManagerAccepted()
			throws Exception {
		GroupTrPost accepted = new GroupTrPost(groupId, memberKey, "Member",
				GroupTrBody.encodeText("accepted post"), 1000L, 1L, false);
		when(manager.getRecentPosts(any()))
				.thenReturn(Collections.singletonList(accepted));
		ProbeConversation a = open();
		clearInvocations(manager);

		a.eventOccurred(new GroupPostReceivedEvent(new ContactId(1),
				new MessageId(new byte[32]), groupId, 1L, strangerKey,
				"Stranger", GroupTrBody.encodeText("never accepted"), 2000L,
				0L));
		runIo();
		shadowOf(Looper.getMainLooper()).idle();

		verify(manager, atLeast(1)).getRecentPosts(any());
		RecyclerView list = a.findViewById(R.id.postsRecycler);
		assertEquals("only the accepted post is listed", 1,
				list.getAdapter().getItemCount());
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		String shown = list.findViewHolderForAdapterPosition(0).itemView
				.toString();
		StringBuilder text = new StringBuilder();
		collectText(list.findViewHolderForAdapterPosition(0).itemView, text);
		assertTrue(text.toString().contains("accepted post"));
		assertFalse("a post from the raw event is shown: " + shown,
				text.toString().contains("never accepted"));
	}

	private static void collectText(View v, StringBuilder out) {
		if (v instanceof TextView) out.append(((TextView) v).getText());
		if (v instanceof android.view.ViewGroup) {
			android.view.ViewGroup g = (android.view.ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collectText(g.getChildAt(i), out);
			}
		}
	}

	@Test
	public void membersOutOfReachAreShown() throws Exception {
		ProbeConversation a = open();

		TextView banner = a.findViewById(R.id.reachBanner);
		assertEquals(View.VISIBLE, banner.getVisibility());
		assertTrue(banner.getText().toString().startsWith("1 member"));
	}

	private ProbeConversation open() {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		ProbeConversation a =
				Robolectric.buildActivity(ProbeConversation.class, i)
						.setup().get();
		runIo();
		return a;
	}

	private static void runIo() {
		while (!ProbeConversation.io.isEmpty()) {
			Runnable r = ProbeConversation.io.remove(0);
			r.run();
			shadowOf(Looper.getMainLooper()).idle();
		}
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
