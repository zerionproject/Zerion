package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Looper;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupConversationReloadTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {

		static GroupTrManager manager;
		static ExecutorService io;

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = mock(IdentityManager.class, RETURNS_DEEP_STUBS);
			ioExecutor = io;
		}
	}

	private final byte[] groupId;
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] memberKey = key((byte) 3);

	public GroupConversationReloadTest() throws Exception {
		groupId = StringUtils.fromHexString(GROUP_HEX);
	}

	@After
	public void tearDown() throws Exception {
		if (ProbeConversation.io != null) {
			ProbeConversation.io.shutdownNow();
			ProbeConversation.io.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aSlowEarlierReloadDoesNotLeaveAnOlderSnapshotOnScreen()
			throws Exception {
		GroupTrManager m = mock(GroupTrManager.class);
		when(m.getGroup(any())).thenReturn(state("Member"));
		List<GroupTrPost> older = posts(1, "Alice");
		List<GroupTrPost> newer = posts(2, "Alice");
		AtomicInteger phase = new AtomicInteger();
		AtomicInteger calls = new AtomicInteger();
		when(m.getRecentPosts(any())).thenAnswer(inv -> {
			int held = phase.get();
			if (held == 0) return new ArrayList<GroupTrPost>();
			List<GroupTrPost> read = held == 1 ? older : newer;
			if (calls.incrementAndGet() == 1) Thread.sleep(400);
			return read;
		});
		ProbeConversation a = open(m);

		phase.set(1);
		a.eventOccurred(raw());
		Thread.sleep(100);
		phase.set(2);
		a.eventOccurred(raw());
		settle();

		RecyclerView list = a.findViewById(R.id.postsRecycler);
		assertEquals("an older snapshot was left on screen", 2,
				list.getAdapter().getItemCount());
	}

	@Test
	public void aPostIsHeadedByTheNameTheMemberIsKnownBy() throws Exception {
		GroupTrManager m = mock(GroupTrManager.class);
		when(m.getGroup(any())).thenReturn(state("Mallory"));
		when(m.getRecentPosts(any())).thenReturn(posts(1, "Alice"));
		ProbeConversation a = open(m);

		RecyclerView list = a.findViewById(R.id.postsRecycler);
		GroupTrPostAdapter adapter = (GroupTrPostAdapter) list.getAdapter();
		assertEquals(1, adapter.getItemCount());
		GroupTrPostAdapter.PostHolder h = adapter.onCreateViewHolder(list,
				adapter.getItemViewType(0));
		adapter.onBindViewHolder(h, 0);
		String label = ((TextView) h.itemView.findViewById(R.id.senderName))
				.getText().toString();
		assertTrue("the post is headed only by the name its sender chose: "
				+ label, label.contains("Mallory"));
		assertTrue(label.contains("Alice"));
	}

	private ProbeConversation open(GroupTrManager m) throws Exception {
		ProbeConversation.manager = m;
		ProbeConversation.io = Executors.newFixedThreadPool(2);
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		ProbeConversation a =
				Robolectric.buildActivity(ProbeConversation.class, i)
						.setup().get();
		settle();
		return a;
	}

	private static void settle() throws Exception {
		for (int k = 0; k < 6; k++) {
			Thread.sleep(250);
			shadowOf(Looper.getMainLooper()).idle();
		}
	}

	private GroupPostReceivedEvent raw() {
		return new GroupPostReceivedEvent(new ContactId(1),
				new MessageId(new byte[32]), groupId, 1L, memberKey, "Alice",
				"hi".getBytes(StandardCharsets.UTF_8), 1L, 0L);
	}

	private GroupTrState state(String memberName) {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Creator", 0L, 0L,
				MemberRole.CREATOR));
		members.add(new GroupTrMember(memberKey, memberName, 0L, 1L));
		return new GroupTrState(groupId, "Group", new byte[32], creatorKey,
				"Creator", 0L, 1L, false, members);
	}

	private List<GroupTrPost> posts(int n, String alias) {
		List<GroupTrPost> out = new ArrayList<>();
		for (int k = 0; k < n; k++) {
			out.add(new GroupTrPost(groupId, memberKey, alias,
					("post " + k).getBytes(StandardCharsets.UTF_8),
					1_000L + k, 1L, false));
		}
		return out;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
