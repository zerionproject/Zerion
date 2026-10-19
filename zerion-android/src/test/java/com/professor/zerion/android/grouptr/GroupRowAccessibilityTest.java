package com.professor.zerion.android.grouptr;

import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.conversation.TextForwarder;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrSendResult;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.util.StringUtils;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupRowAccessibilityTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {

		static GroupTrManager manager;
		static IdentityManager identity;
		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = identity;
			textForwarder = mock(TextForwarder.class);
			ioExecutor = io::add;
		}
	}

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] memberKey = key((byte) 3);
	private byte[] groupId;
	private GroupTrManager manager;
	private GroupTrPost mine;
	private GroupTrPost theirs;
	private ActivityController<ProbeConversation> controller;
	private ProbeConversation activity;

	@Before
	public void setUp() throws Exception {
		groupId = StringUtils.fromHexString(GROUP_HEX);
		ProbeConversation.io.clear();
		manager = mock(GroupTrManager.class);
		IdentityManager identity = mock(IdentityManager.class);
		when(identity.getLocalAuthor()).thenReturn(new LocalAuthor(
				new AuthorId(key((byte) 9)), 1, "Local",
				new SignaturePublicKey(localKey),
				new SignaturePrivateKey(new byte[32])));
		ProbeConversation.identity = identity;
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Creator", 0L, 0L,
				MemberRole.CREATOR));
		members.add(new GroupTrMember(localKey, "Local", 0L, 0L));
		members.add(new GroupTrMember(memberKey, "Member", 0L, 0L));
		when(manager.getGroup(any())).thenReturn(new GroupTrState(groupId,
				"Group", new byte[32], creatorKey, "Creator", 0L, 1L, false,
				members));
		when(manager.getMembersOutOfReach(any()))
				.thenReturn(Collections.emptyList());
		mine = post(localKey, GroupTrBody.encodeText("my post"), 1_000L,
				key((byte) 10));
		theirs = post(memberKey, GroupTrBody.encodeText("their post"),
				2_000L, key((byte) 11));
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs));
		when(manager.countMembersWithPostActions(any())).thenReturn(2);
		when(manager.countMembersWithoutPostActions(any())).thenReturn(0);
		ProbeConversation.manager = manager;
		activity = open();
	}

	private static List<String> customActions(View v) {
		List<String> out = new ArrayList<>();
		for (AccessibilityNodeInfo.AccessibilityAction a :
				v.createAccessibilityNodeInfo().getActionList()) {
			if (SelectionAction.byId(a.getId()) != null
					|| a.getId() == safetyCode()) {
				out.add(String.valueOf(a.getLabel()));
			}
		}
		return out;
	}

	@Test
	public void myPostOffersReplyCopyAndDelete() {
		assertEquals("[Reply, Copy, Delete]",
				customActions(holder(0).itemView).toString());
	}

	@Test
	public void aMembersPostAlsoOffersTheirSafetyCode() {
		View row = holder(1).itemView;
		assertEquals("[Reply, Copy, Delete, Safety code]",
				customActions(row).toString());
		assertTrue(row.performAccessibilityAction(safetyCode(), null));
		idle();
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull(d);
		assertTrue(d.isShowing());
	}

	@Test
	public void copyFromThePostCopiesWithoutTheBar() throws Exception {
		assertTrue(holder(1).itemView.performAccessibilityAction(
				R.id.action_copy, null));
		idle();
		ClipboardManager cm = (ClipboardManager) activity.getSystemService(
				Context.CLIPBOARD_SERVICE);
		assertEquals("their post", cm.getPrimaryClip().getItemAt(0)
				.getText().toString());
		assertFalse(bar().isShowing());
		assertEquals(0, selected());
	}

	@Test
	public void replyFromThePostQuotesIt() throws Exception {
		assertTrue(holder(1).itemView.performAccessibilityAction(
				R.id.action_reply, null));
		idle();
		assertEquals(View.VISIBLE, activity.findViewById(
				R.id.groupReplyPreview).getVisibility());
		assertFalse(bar().isShowing());
	}

	@Test
	public void deleteFromThePostAsksTheSameQuestion() throws Exception {
		assertTrue(holder(0).itemView.performAccessibilityAction(
				R.id.action_delete, null));
		runIo();
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull(d);
		assertTrue(d.isShowing());
		assertTrue(bar().isShowing());
		assertEquals(1, selected());
	}

	private static long allocated() throws Exception {
		Object threads = Class.forName("java.lang.management.ManagementFactory")
				.getMethod("getThreadMXBean").invoke(null);
		return (Long) Class.forName("com.sun.management.ThreadMXBean")
				.getMethod("getThreadAllocatedBytes", long.class)
				.invoke(threads, Thread.currentThread().getId());
	}

	@Test
	public void readingAPictureRowNeverCopiesThePicture() throws Exception {
		byte[] picture = new byte[8 * 1024 * 1024];
		GroupTrPost image = post(memberKey, GroupTrBody.encodeImage(picture,
				"image/jpeg"), 3_000L, key((byte) 12));
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs, image));
		reload();
		runIo();
		View row = holder(2).itemView;
		assertEquals("[Reply, Delete, Safety code]",
				customActions(row).toString());
		allocated();
		long before = allocated();
		for (int i = 0; i < 20; i++) row.createAccessibilityNodeInfo();
		long used = allocated() - before;
		assertTrue("20 reads allocated " + used + " bytes",
				used < picture.length);
	}

	private static int changes(ShadowAccessibilityManager talkBack) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
		int n = 0;
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType()
					== AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
				n++;
			}
		}
		return n;
	}

	@Test
	public void aPostNotSentYetStaysUntappableAndQuietWhenBoundAgain()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		EditText input = activity.findViewById(R.id.messageInput);
		input.setText("hello");
		activity.findViewById(R.id.sendButton).performClick();
		runIo();
		GroupTrPostAdapter.PostHolder pending =
				(GroupTrPostAdapter.PostHolder) holder(2);
		View bubble = pending.itemView.findViewById(R.id.bubble);
		assertFalse(pending.itemView.isClickable());
		assertFalse(bubble.isClickable());
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				activity.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		GroupTrPost post = ((GroupTrPostAdapter) adapter()).postAt(2);
		assertNotNull(post);
		java.lang.reflect.Method selectable = GroupTrPostAdapter.PostHolder
				.class.getDeclaredMethod("selectable", View.class,
						GroupTrPost.class, boolean.class);
		selectable.setAccessible(true);
		int before = changes(talkBack);
		for (int i = 0; i < 3; i++) {
			selectable.invoke(pending, pending.itemView, post, true);
			selectable.invoke(pending, bubble, post, true);
		}
		assertEquals("setting up a post not sent again is quiet", before,
				changes(talkBack));
		assertFalse(pending.itemView.isClickable());
		assertFalse(bubble.isClickable());
		assertFalse(pending.itemView.performClick());

		GroupTrPostAdapter.PostHolder stored =
				(GroupTrPostAdapter.PostHolder) holder(1);
		assertTrue(stored.itemView.isClickable());
		assertTrue(stored.itemView.findViewById(R.id.bubble).isClickable());
		assertTrue(stored.itemView.hasOnClickListeners());
	}

	@Test
	public void senderWordsAndTimeReadAsOneItem() {
		View row = holder(1).itemView;
		assertTrue(row.createAccessibilityNodeInfo().isScreenReaderFocusable());
		for (int id : new int[] {R.id.senderName, R.id.bubble,
				R.id.postText}) {
			AccessibilityNodeInfo part = row.findViewById(id)
					.createAccessibilityNodeInfo();
			assertFalse("part " + id + " is its own stop", part.isClickable()
					|| part.isLongClickable() || part.isFocusable());
		}
		TextView sender = row.findViewById(R.id.senderName);
		assertTrue("a sighted tap still opens the code",
				sender.hasOnClickListeners());
	}

	@Test
	public void aPostThatGetsThroughOnRetryIsSaidOnce() throws Exception {
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				activity.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		when(manager.getRecentPosts(any()))
				.thenReturn(Collections.singletonList(theirs));
		reload();
		runIo();
		holder(0);
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException())
				.thenReturn(new GroupTrSendResult(key((byte) 20), 2, 2, 0, 0));
		EditText input = activity.findViewById(R.id.messageInput);
		input.setText("hello");
		activity.findViewById(R.id.sendButton).performClick();
		runIo();
		holder(1);
		idle();
		assertTrue("the Retry message tells of the failure, not the row: "
				+ said(talkBack), said(talkBack).isEmpty());

		holder(1).itemView.findViewById(R.id.messageRetryButton)
				.performClick();
		idle();
		holder(1);
		runIo();
		holder(1);
		idle();
		List<String> said = said(talkBack);
		assertEquals(said.toString(), 1, said.size());
		assertTrue(said.get(0), said.get(0).contains("hello"));
		assertTrue(said.get(0), said.get(0).contains(activity.getString(
				R.string.message_state_waiting)));

		adapter().notifyDataSetChanged();
		holder(1);
		idle();
		assertEquals("a rebind says nothing", 1, said(talkBack).size());
	}

	private static int safetyCode() {
		try {
			return R.id.class.getField("action_safety_code").getInt(null);
		} catch (ReflectiveOperationException e) {
			return View.NO_ID;
		}
	}

	private static List<String> said(ShadowAccessibilityManager talkBack) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
		List<String> out = new ArrayList<>();
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
				out.add(e.getText().toString());
			}
		}
		return out;
	}

	private void reload() {
		activity.eventOccurred(
				new org.zerionproject.app.api.messaging.event
						.GroupTrLocalStateChangedEvent(groupId,
						org.zerionproject.app.api.messaging.event
								.GroupTrLocalStateChangedEvent.Kind.UPDATED));
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
	}

	private RecyclerView.Adapter<?> adapter() {
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		return list.getAdapter();
	}

	private SelectionBar bar() throws Exception {
		return (SelectionBar) field("selectionBar");
	}

	private int selected() throws Exception {
		return ((Collection<?>) field("selected")).size();
	}

	private Object field(String name) throws Exception {
		Field f = GroupTrConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(activity);
	}

	private GroupTrPost post(byte[] sender, byte[] body, long timestamp,
			byte[] id) {
		return new GroupTrPost(groupId, sender, "Name", body, timestamp, 1L,
				Arrays.equals(sender, localKey), 0L, timestamp, id);
	}

	private ProbeConversation open() {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		controller = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup();
		activity = controller.get();
		runIo();
		return activity;
	}

	@After
	public void tearDown() {
		controller.pause().stop().destroy();
		runIo();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void runIo() {
		idle();
		while (!ProbeConversation.io.isEmpty()) {
			Runnable r = ProbeConversation.io.remove(0);
			r.run();
			idle();
		}
		idle();
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
