package com.professor.zerion.android.channel;

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
import com.professor.zerion.android.api.AndroidNotificationManager;
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
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class CommentRowAccessibilityTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long PARENT = 4L;

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager;

	public static class ProbeComments extends ChannelCommentsActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			eventBus = mock(EventBus.class);
			notificationManager = mock(AndroidNotificationManager.class);
			ioExecutor = io::add;
		}
	}

	private final byte[] channelId = new byte[32];
	private final byte[] ownerKey = key((byte) 1);
	private final byte[] myKey = key((byte) 5);
	private final byte[] otherKey = key((byte) 7);
	private ActivityController<ProbeComments> controller;
	private ProbeComments activity;

	@Before
	public void setUp() throws Exception {
		io.clear();
		manager = mock(ChannelManager.class);
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.areCommentsEnabled(any(), anyLong())).thenReturn(true);
		when(manager.getComments(any(), anyLong())).thenReturn(Arrays.asList(
				new ChannelComment(PARENT, 1L, "hello", "Alice", otherKey,
						new byte[0], HOUR),
				new ChannelComment(PARENT, 2L, "mine", "Me", myKey,
						new byte[0], 2 * HOUR)));
		when(manager.getChannel(any())).thenReturn(new ChannelState(channelId,
				new byte[16], ownerKey, new byte[0], "News", "", null, HOUR,
				true, null, "", 1L, false, 0L));
		when(manager.getMyChannelPublicKey(any())).thenReturn(myKey);
		Intent i = ChannelCommentsActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT);
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeComments.class);
		controller = Robolectric.buildActivity(ProbeComments.class, i)
				.setup();
		activity = controller.get();
		runIo();
	}

	@After
	public void tearDown() {
		controller.pause().stop().destroy();
		idle();
	}

	private static List<String> customActions(View v) {
		List<String> out = new ArrayList<>();
		for (AccessibilityNodeInfo.AccessibilityAction a :
				v.createAccessibilityNodeInfo().getActionList()) {
			if (SelectionAction.byId(a.getId()) != null) {
				out.add(String.valueOf(a.getLabel()));
			}
		}
		return out;
	}

	@Test
	public void aCommentOffersCopyAndTheMemberSheet() {
		assertEquals("[Copy, Member]",
				customActions(holder(0).itemView).toString());
	}

	@Test
	public void copyFromTheCommentCopiesWithoutTheBar() throws Exception {
		assertTrue(holder(0).itemView.performAccessibilityAction(
				R.id.action_copy, null));
		runIo();
		ClipboardManager cm = (ClipboardManager) activity.getSystemService(
				Context.CLIPBOARD_SERVICE);
		assertEquals("hello", cm.getPrimaryClip().getItemAt(0).getText()
				.toString());
		assertFalse(bar().isShowing());
	}

	@Test
	public void memberFromTheCommentOpensTheSheetLikeTheName()
			throws Exception {
		assertTrue(holder(0).itemView.performAccessibilityAction(
				R.id.action_member, null));
		runIo();
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue("the member sheet opens: " + d, d instanceof
				com.google.android.material.bottomsheet.BottomSheetDialog);
		assertEquals("Alice", ((TextView) d.findViewById(
				R.id.channelMemberName)).getText().toString());
		assertFalse(bar().isShowing());
	}

	@Test
	public void theNameWordsAndTimeReadAsOneItem() {
		View row = holder(0).itemView;
		assertTrue(row.createAccessibilityNodeInfo().isScreenReaderFocusable());
		AccessibilityNodeInfo author = row.findViewById(R.id.commentAuthor)
				.createAccessibilityNodeInfo();
		assertFalse("the name is not a stop of its own",
				author.isClickable() || author.isFocusable());
		TextView name = row.findViewById(R.id.commentAuthor);
		assertTrue("a sighted tap on the name still works",
				name.hasOnClickListeners());
	}

	@Test
	public void rowActionsStepAsideWhileTheBarIsOpen() throws Exception {
		assertTrue(holder(0).itemView.performLongClick());
		idle();
		assertTrue(bar().isShowing());
		assertTrue(customActions(holder(1).itemView).isEmpty());
	}

	@Test
	public void aCommentThatGetsThroughOnRetryIsSaidOnce() throws Exception {
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				activity.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenThrow(new DbException()).thenReturn(9L);
		EditText input = activity.findViewById(R.id.commentsComposeInput);
		input.setText("see you there");
		activity.findViewById(R.id.commentsComposeSendButton).performClick();
		runIo();
		holder(2);
		assertTrue("the failure is left to the Retry message: "
				+ said(talkBack), said(talkBack).isEmpty());

		holder(2).itemView.findViewById(R.id.messageRetryButton)
				.performClick();
		runIo();
		holder(2);
		idle();
		List<String> said = said(talkBack);
		assertEquals(said.toString(), 1, said.size());
		assertTrue(said.get(0), said.get(0).contains("see you there"));
		assertTrue(said.get(0), said.get(0).contains(activity.getString(
				R.string.message_state_sent)));
		holder(2).itemView.requestLayout();
		holder(2);
		assertEquals("a rebind says nothing", 1, said(talkBack).size());
	}

	@Test
	public void myNameOnlyKeepsATallTargetWhereTappingItDoesSomething()
			throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenThrow(new DbException());
		EditText input = activity.findViewById(R.id.commentsComposeInput);
		input.setText("on my way");
		activity.findViewById(R.id.commentsComposeSendButton).performClick();
		runIo();
		float density = activity.getResources().getDisplayMetrics().density;
		TextView sending = holder(2).itemView.findViewById(
				R.id.commentAuthor);
		assertEquals(activity.getString(R.string.channels_comment_you),
				sending.getText().toString());
		assertFalse(sending.isClickable());
		assertEquals("no tall target above a name that does nothing", 0,
				sending.getMinHeight());
		TextView mine = holder(1).itemView.findViewById(R.id.commentAuthor);
		assertTrue("my own sent comment still opens my member sheet",
				mine.hasOnClickListeners());
		assertEquals(Math.round(48 * density), mine.getMinHeight());
		TextView theirs = holder(0).itemView.findViewById(R.id.commentAuthor);
		assertEquals(Math.round(48 * density), theirs.getMinHeight());
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

	private SelectionBar bar() throws Exception {
		Field f = ChannelCommentsActivity.class.getDeclaredField(
				"selectionBar");
		f.setAccessible(true);
		return (SelectionBar) f.get(activity);
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = activity.findViewById(R.id.commentsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
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

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
