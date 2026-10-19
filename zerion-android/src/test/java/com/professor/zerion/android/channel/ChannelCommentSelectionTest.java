package com.professor.zerion.android.channel;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectedLook;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;
import com.professor.zerion.android.selection.SelectionBarTest;
import com.professor.zerion.android.util.UiUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.event.EventBus;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.ContrastMath.over;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelCommentSelectionTest {

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
		asSubscriber();
	}

	private void asSubscriber() throws Exception {
		when(manager.getChannel(any())).thenReturn(state(false));
		when(manager.getMyChannelPublicKey(any())).thenReturn(myKey);
	}

	private void asOwner() throws Exception {
		when(manager.getChannel(any())).thenReturn(state(true));
		when(manager.getMyChannelPublicKey(any())).thenReturn(ownerKey);
	}

	private ChannelState state(boolean owner) {
		return new ChannelState(channelId, new byte[16], ownerKey, new byte[0],
				"News", "", null, HOUR, true, null, "", 1L, owner, 0L);
	}

	private ProbeComments open(Intent i) {
		return open(i, AppCompatDelegate.MODE_NIGHT_UNSPECIFIED);
	}

	private ProbeComments open(Intent i, int night) {
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeComments.class);
		ProbeComments a = SelectedLook.start(Robolectric.buildActivity(
				ProbeComments.class, i), night);
		activity = a;
		runIo();
		return a;
	}

	private ProbeComments open() {
		return open(ChannelCommentsActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT));
	}

	@Test
	public void aLongPressSelectsAComment() throws Exception {
		open();

		assertTrue(holder(0).itemView.performLongClick());
		idle();

		assertTrue(bar().isShowing());
		assertTrue(holder(0).itemView.isActivated());
		assertTrue(holder(0).itemView.isSelected());
		assertTrue("the selected comment is painted as selected",
				SelectionBarTest.drawsHighlight(holder(0).itemView));
		assertFalse(holder(1).itemView.isActivated());
		assertFalse(SelectionBarTest.drawsHighlight(holder(1).itemView));
		assertEquals("a subscriber can copy a comment, see its details and "
				+ "open the member", EnumSet.of(SelectionAction.COPY,
				SelectionAction.MEMBER, SelectionAction.INFO),
				bar().shownActions());
	}

	@Test
	public void theOwnerFindsNoModerationInTheBar() throws Exception {
		asOwner();
		open();

		holder(0).itemView.performLongClick();
		idle();

		assertEquals("moderation lives in the member sheet only",
				EnumSet.of(SelectionAction.COPY, SelectionAction.MEMBER,
						SelectionAction.INFO), bar().shownActions());
		androidx.appcompat.widget.PopupMenu more = bar().openMore();
		assertNotNull(more);
		for (int i = 0; i < more.getMenu().size(); i++) {
			android.view.MenuItem item = more.getMenu().getItem(i);
			if (item.isVisible()) {
				assertTrue(item.getItemId() == R.id.action_info
						|| item.getItemId() == R.id.action_member);
			}
		}
	}

	@Test
	public void memberUnderMoreOpensTheMemberSheet() throws Exception {
		asOwner();
		open();
		holder(0).itemView.performLongClick();
		idle();

		assertTrue(bar().perform(SelectionAction.MEMBER));
		runIo();

		android.app.Dialog d = ShadowDialog.getLatestDialog();
		assertTrue("the member sheet opens: " + d, d instanceof
				com.google.android.material.bottomsheet.BottomSheetDialog);
		assertTrue(d.isShowing());
		assertEquals("Alice", ((TextView) d.findViewById(
				R.id.channelMemberName)).getText().toString());
		assertFalse(bar().isShowing());
	}

	@Test
	public void tappingTheAuthorOpensTheMemberSheet() throws Exception {
		open();
		TextView author = holder(0).itemView.findViewById(R.id.commentAuthor);
		assertTrue(author.isClickable());

		author.performClick();
		runIo();

		android.app.Dialog d = ShadowDialog.getLatestDialog();
		assertTrue("the member sheet opens: " + d, d instanceof
				com.google.android.material.bottomsheet.BottomSheetDialog);
		assertFalse("a subscriber sees no moderation", d.findViewById(
				R.id.channelMemberBlock).getVisibility() == View.VISIBLE);
		assertFalse(bar().isShowing());
	}

	@Test
	public void tappingTheAuthorWhileSelectingSelectsTheComment()
			throws Exception {
		open();
		holder(0).itemView.performLongClick();
		idle();

		holder(1).itemView.findViewById(R.id.commentAuthor).performClick();
		idle();

		assertEquals(2, bar().getCount());
		assertNull("no sheet opens while selecting",
				ShadowDialog.getLatestDialog());
	}

	@Test
	public void severalCommentsAreCopiedTogether() throws Exception {
		open();
		holder(0).itemView.performLongClick();
		idle();
		holder(1).itemView.performClick();
		idle();

		assertTrue(holder(0).itemView.isActivated());
		assertTrue(holder(1).itemView.isActivated());
		assertEquals(EnumSet.of(SelectionAction.COPY), bar().shownActions());
		assertTrue(bar().perform(SelectionAction.COPY));
		idle();

		ClipboardManager cm = (ClipboardManager) activity.getSystemService(
				Context.CLIPBOARD_SERVICE);
		assertEquals("hello\nmine", cm.getPrimaryClip().getItemAt(0)
				.getText().toString());
		assertFalse(bar().isShowing());
	}

	@Test
	public void aWaitingCommentCannotBeSelected() throws Exception {
		when(manager.getComments(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(new ChannelQueuedItem(9L,
						ChannelQueuedItem.Kind.COMMENT, PARENT, "later", false,
						System.currentTimeMillis(), false)));
		open();

		assertFalse(holder(0).itemView.performLongClick());
		idle();
		assertFalse(bar().isShowing());
	}

	@Test
	public void messageInfoSaysWhoWroteIt() throws Exception {
		open();
		holder(0).itemView.performLongClick();
		idle();
		assertTrue(bar().perform(SelectionAction.INFO));
		idle();

		String text = message((AlertDialog) ShadowDialog.getLatestDialog());
		assertTrue(text, text.contains(activity.getString(
				R.string.message_info_from)));
		assertTrue(text, text.contains("Alice"));
	}

	@Test
	public void backClearsTheSelection() throws Exception {
		open();
		holder(0).itemView.performLongClick();
		idle();

		SelectionBarTest.pressBack(activity);

		assertFalse(bar().isShowing());
		assertFalse(holder(0).itemView.isActivated());
		assertFalse(activity.isFinishing());
	}

	@Test
	public void replyingFromTheFeedOpensTheComposer() throws Exception {
		open(ChannelCommentsActivity.writeIntent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT));

		EditText input = activity.findViewById(R.id.commentsComposeInput);
		assertTrue(input.isFocused());
	}

	private int color(int attr) {
		return UiUtils.resolveColorAttribute(activity, attr);
	}

	@Test
	public void aSelectedCommentTintsItsBubbleAndDrawsNoOutline()
			throws Exception {
		open();
		holder(0).itemView.performLongClick();
		idle();
		holder(1).itemView.performClick();
		idle();

		View theirs = holder(0).itemView.findViewById(R.id.commentCard);
		int bubbleIn = color(R.attr.zerionColorBubbleIn);
		assertEquals(bubbleIn, SelectedLook.painted(theirs, false));
		assertEquals(over(color(R.attr.zerionColorMessageSelectedReceived),
				bubbleIn), SelectedLook.painted(theirs));
		assertEquals(SelectedLook.strokeWidth(theirs, false),
				SelectedLook.strokeWidth(theirs, true));
		assertEquals(color(R.attr.zerionColorOutline),
				SelectedLook.strokeColor(theirs, true));
		View mine = holder(1).itemView.findViewById(R.id.commentCard);
		int own = color(R.attr.zerionColorBubbleOut);
		assertEquals(own, SelectedLook.painted(mine, false));
		assertTrue(SelectedLook.near(over(color(
				R.attr.zerionColorMessageSelectedOwn), own),
				SelectedLook.painted(mine)));
		for (int i = 0; i < 2; i++) {
			assertNull(holder(i).itemView.getBackground());
		}
	}

	private List<String> unreadableWhenSelected(int night) throws Exception {
		when(manager.getComments(any(), anyLong())).thenReturn(Arrays.asList(
				new ChannelComment(PARENT, 1L, "map: https://example.org/map",
						"Alice", otherKey, new byte[0], HOUR),
				new ChannelComment(PARENT, 2L, "mine", "Me", myKey,
						new byte[0], 2 * HOUR)));
		open(ChannelCommentsActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT), night);
		holder(0).itemView.performLongClick();
		idle();
		holder(1).itemView.performClick();
		idle();
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			assertTrue(SelectedLook.drawsSelected(holder(i).itemView));
			problems.addAll(SelectedLook.unreadable(holder(i).itemView, 4.5,
					4.5));
		}
		return problems;
	}

	@Test
	public void wordsOnASelectedCommentStayReadableInLight() throws Exception {
		List<String> problems = unreadableWhenSelected(
				AppCompatDelegate.MODE_NIGHT_NO);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void wordsOnASelectedCommentStayReadableInDark() throws Exception {
		List<String> problems = unreadableWhenSelected(
				AppCompatDelegate.MODE_NIGHT_YES);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void selectingACommentMovesNothing() throws Exception {
		open();
		List<List<String>> before = Arrays.asList(
				SelectedLook.boxes(holder(0).itemView),
				SelectedLook.boxes(holder(1).itemView));

		holder(0).itemView.performLongClick();
		idle();
		holder(1).itemView.performClick();
		idle();

		for (int i = 0; i < 2; i++) {
			assertTrue(SelectedLook.drawsSelected(holder(i).itemView));
			assertEquals(before.get(i), SelectedLook.boxes(holder(i).itemView));
		}
	}

	@Test
	public void threeSelectedCommentsAreEachToldAsSelected() throws Exception {
		when(manager.getComments(any(), anyLong())).thenReturn(Arrays.asList(
				new ChannelComment(PARENT, 1L, "hello", "Alice", otherKey,
						new byte[0], HOUR),
				new ChannelComment(PARENT, 2L, "mine", "Me", myKey,
						new byte[0], 2 * HOUR),
				new ChannelComment(PARENT, 3L, "again", "Alice", otherKey,
						new byte[0], 3 * HOUR),
				new ChannelComment(PARENT, 4L, "later", "Alice", otherKey,
						new byte[0], 4 * HOUR)));
		open();
		holder(0).itemView.performLongClick();
		idle();
		holder(1).itemView.performClick();
		idle();
		holder(2).itemView.performClick();
		idle();

		assertEquals(3, bar().getCount());
		for (int i = 0; i < 3; i++) {
			View row = holder(i).itemView;
			assertTrue(SelectedLook.toldSelected(row));
			assertTrue(SelectedLook.toldSelected(
					row.findViewById(R.id.commentCard)));
			assertTrue(SelectedLook.drawsSelected(row));
		}
		assertFalse(SelectedLook.toldSelected(holder(3).itemView));
		assertFalse(SelectedLook.drawsSelected(holder(3).itemView));
	}

	private SelectionBar bar() throws Exception {
		Field f = ChannelCommentsActivity.class.getDeclaredField(
				"selectionBar");
		f.setAccessible(true);
		return (SelectionBar) f.get(activity);
	}

	private static String message(AlertDialog d) {
		assertNotNull(d);
		TextView m = d.findViewById(android.R.id.message);
		assertNotNull(m);
		return m.getText().toString();
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
