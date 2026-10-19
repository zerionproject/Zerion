package com.professor.zerion.android.channel;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
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
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelFeedSelectionTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager;

	public static class ProbeFeed extends ChannelFeedActivity {

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
	private ProbeFeed feed;

	private void stub(boolean owner, ChannelPost... posts) throws Exception {
		io.clear();
		manager = mock(ChannelManager.class);
		ChannelState s = new ChannelState(channelId, new byte[16],
				new byte[32], new byte[0], "News", "", null, HOUR, true, null,
				"", 1L, owner, 0L);
		when(manager.getChannel(any())).thenReturn(s);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Arrays.asList(posts));
		when(manager.canPost(any())).thenReturn(owner);
		when(manager.getCommentExceptions(any()))
				.thenReturn(Collections.emptySet());
		when(manager.getMyChannelPublicKey(any())).thenReturn(key((byte) 5));
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.refreshChannelTick(any())).thenReturn(true);
	}

	@Before
	public void setUp() throws Exception {
		stub(true, post(1L, "Route for Saturday"), post(2L, "We need drivers"));
	}

	private ProbeFeed open() {
		return open(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED);
	}

	private ProbeFeed open(int night) {
		Intent i = ChannelFeedActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId);
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeFeed.class);
		ProbeFeed f = SelectedLook.start(Robolectric.buildActivity(
				ProbeFeed.class, i), night);
		feed = f;
		runIo();
		return f;
	}

	private static ChannelPost post(long seq, String body) {
		return new ChannelPost(new byte[32], seq, new byte[32],
				System.currentTimeMillis() / HOUR * HOUR, body,
				Collections.emptyList(), 0L, new byte[64], true, null, null,
				false, ChannelPost.FORMAT_V2, null);
	}

	private static ChannelPost withFiles(long seq, String body,
			ChannelPost.ChannelAttachment... files) {
		return new ChannelPost(new byte[32], seq, new byte[32],
				System.currentTimeMillis() / HOUR * HOUR, body,
				Arrays.asList(files), 0L, new byte[64], true, null, null,
				false, ChannelPost.FORMAT_V2, null);
	}

	@Test
	public void aLongPressSelectsThePostInsteadOfOpeningAList()
			throws Exception {
		open();

		assertTrue(holder(0).itemView.performLongClick());
		idle();

		assertNull("no list of actions pops up", ShadowDialog
				.getLatestDialog());
		assertTrue(bar().isShowing());
		assertTrue(holder(0).itemView.isActivated());
		assertTrue(holder(0).itemView.isSelected());
		assertTrue("the selected post is painted as selected",
				SelectionBarTest.drawsHighlight(holder(0).itemView));
		assertFalse(holder(1).itemView.isActivated());
		assertFalse(SelectionBarTest.drawsHighlight(holder(1).itemView));
	}

	@Test
	public void theOwnerGetsTheOwnerActions() throws Exception {
		open();
		longPress(0);

		assertEquals(EnumSet.of(SelectionAction.REPLY, SelectionAction.REACT,
				SelectionAction.COPY, SelectionAction.DELETE,
				SelectionAction.EDIT, SelectionAction.PIN,
				SelectionAction.COMMENTS, SelectionAction.COMMENTS_OFF,
				SelectionAction.INFO), bar().shownActions());
	}

	@Test
	public void aSubscriberGetsNoOwnerActions() throws Exception {
		stub(false, post(1L, "Route for Saturday"));
		open();
		longPress(0);

		assertEquals(EnumSet.of(SelectionAction.REPLY, SelectionAction.REACT,
				SelectionAction.COPY, SelectionAction.COMMENTS,
				SelectionAction.INFO), bar().shownActions());
	}

	@Test
	public void aDeletedPostCannotBeCopiedReactedToOrChanged()
			throws Exception {
		stub(true, post(1L, ChannelConstants.DELETED_POST_PLACEHOLDER));
		open();
		longPress(0);

		assertEquals(EnumSet.of(SelectionAction.COMMENTS,
				SelectionAction.INFO), bar().shownActions());
	}

	@Test
	public void aWithheldPostCannotBeSelected() throws Exception {
		stub(false, new ChannelPost(new byte[32], 1L, new byte[32],
				System.currentTimeMillis() / HOUR * HOUR, "", Collections
				.emptyList(), 0L, new byte[64], true, null, null, true));
		open();

		assertFalse(holder(0).itemView.performLongClick());
		idle();
		assertFalse(bar().isShowing());
	}

	@Test
	public void severalPostsCanBeCopiedTogether() throws Exception {
		open();
		longPress(0);
		holder(1).itemView.performClick();
		idle();

		assertTrue(holder(0).itemView.isActivated());
		assertTrue(holder(1).itemView.isActivated());
		assertEquals(EnumSet.of(SelectionAction.COPY, SelectionAction.DELETE),
				bar().shownActions());
		holder(1).itemView.performClick();
		idle();
		assertFalse("a second tap lets go of the post",
				holder(1).itemView.isActivated());
		holder(1).itemView.performClick();
		idle();
		assertTrue(bar().perform(SelectionAction.COPY));
		idle();

		ClipboardManager cm = (ClipboardManager) feed.getSystemService(
				Context.CLIPBOARD_SERVICE);
		assertEquals("Route for Saturday\n\nWe need drivers",
				cm.getPrimaryClip().getItemAt(0).getText().toString());
		assertFalse(bar().isShowing());
	}

	@Test
	public void severalOwnPostsAreDeletedAfterOneQuestion() throws Exception {
		open();
		longPress(0);
		holder(1).itemView.performClick();
		idle();

		assertTrue(bar().perform(SelectionAction.DELETE));
		idle();
		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		assertEquals(feed.getResources().getQuantityString(
				R.plurals.channels_posts_delete_confirm, 2, 2),
				message(dialog));
		assertTrue("the question keeps the selection", bar().isShowing());
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runIo();

		verify(manager).deletePost(eq(channelId), eq(1L));
		verify(manager).deletePost(eq(channelId), eq(2L));
		assertFalse(bar().isShowing());
	}

	@Test
	public void cancellingTheDeleteKeepsTheSelection() throws Exception {
		open();
		longPress(0);

		assertTrue(bar().perform(SelectionAction.DELETE));
		idle();
		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		assertEquals(feed.getString(R.string.channels_post_delete_confirm),
				message(dialog));
		dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		runIo();

		assertTrue(bar().isShowing());
		verify(manager, never()).deletePost(any(), anyLong());
	}

	@Test
	public void replyOpensTheCommentsReadyToWrite() throws Exception {
		open();
		longPress(1);

		assertTrue(bar().perform(SelectionAction.REPLY));
		idle();

		Intent next = commentsOpened();
		assertNotNull(next);
		assertTrue(next.getBooleanExtra(ChannelCommentsActivity.EXTRA_WRITE,
				false));
		assertEquals(2L, next.getLongExtra(
				"com.professor.zerion.android.channel.COMMENTS_PARENT_SEQ",
				-1L));
		assertFalse(bar().isShowing());
	}

	private Intent commentsOpened() {
		Intent i;
		while ((i = shadowOf(feed).getNextStartedActivity()) != null) {
			if (i.getComponent() != null && i.getComponent().getClassName()
					.equals(ChannelCommentsActivity.class.getName())) {
				return i;
			}
		}
		return null;
	}

	@Test
	public void pinGoesToTheChannel() throws Exception {
		open();
		longPress(0);

		assertTrue(bar().perform(SelectionAction.PIN));
		runIo();

		verify(manager).pinPost(eq(channelId), eq(1L));
		assertFalse(bar().isShowing());
	}

	@Test
	public void tappingTheCommentsLabelWhileSelectingSelectsThePost()
			throws Exception {
		open();
		longPress(0);

		holder(1).itemView.findViewById(R.id.channelPostCommentBadge)
				.performClick();
		idle();

		assertEquals(2, bar().getCount());
		assertNull("the comments do not open", commentsOpened());
	}

	@Test
	public void messageInfoSaysWhenItWasPosted() throws Exception {
		open();
		longPress(0);

		assertTrue(bar().perform(SelectionAction.INFO));
		idle();

		String text = message((AlertDialog) ShadowDialog.getLatestDialog());
		assertTrue(text, text.contains(feed.getString(
				R.string.message_info_posted)));
		assertFalse(bar().isShowing());
	}

	@Test
	public void backClearsTheSelection() throws Exception {
		open();
		longPress(0);

		SelectionBarTest.pressBack(feed);

		assertFalse(bar().isShowing());
		assertFalse(holder(0).itemView.isActivated());
		assertFalse(feed.isFinishing());
	}

	@Test
	public void lettingGoOfTheLastPostEndsTheSelection() throws Exception {
		open();
		longPress(0);

		holder(0).itemView.performClick();
		idle();

		assertFalse(bar().isShowing());
		assertFalse(holder(0).itemView.isActivated());
		assertFalse("a tap opens nothing once nothing is selected",
				holder(0).itemView.isClickable());
	}

	@Test
	public void aLongPressOnALinkInThePostSelectsItWithoutOpeningTheLink()
			throws Exception {
		stub(true, post(1L, "https://example.org/route"),
				post(2L, "We need drivers"));
		open();
		TextView body = holder(0).itemView
				.findViewById(R.id.channelPostBodyView);
		assertNotNull("the body is link aware", body.getMovementMethod());

		SelectionBarTest.longPress(body);

		assertTrue(bar().isShowing());
		assertTrue(holder(0).itemView.isActivated());
		assertNull("the long press does not ask about the link",
				ShadowDialog.getLatestDialog());

		bar().close();
		idle();
		body = holder(0).itemView.findViewById(R.id.channelPostBodyView);
		SelectionBarTest.tap(body);
		assertNotNull("a tap still asks about the link",
				ShadowDialog.getLatestDialog());
		assertFalse(bar().isShowing());
	}

	@Test
	public void aTapOnTheBodyWhileSelectingTogglesThePost() throws Exception {
		open();
		longPress(0);
		TextView body = holder(1).itemView
				.findViewById(R.id.channelPostBodyView);

		SelectionBarTest.tap(body);
		assertTrue("the tapped post joins the selection",
				holder(1).itemView.isActivated());
		assertTrue(holder(0).itemView.isActivated());

		SelectionBarTest.tap(body);
		assertFalse("a second tap lets it go",
				holder(1).itemView.isActivated());
		assertTrue(bar().isShowing());
	}

	@Test
	public void aTapOnALinkWhileSelectingTogglesThePostInsteadOfOpeningIt()
			throws Exception {
		stub(true, post(1L, "https://example.org/route"),
				post(2L, "We need drivers"));
		open();
		longPress(1);
		TextView body = holder(0).itemView
				.findViewById(R.id.channelPostBodyView);

		SelectionBarTest.tap(body);

		assertTrue(holder(0).itemView.isActivated());
		assertNull("the link does not open while selecting",
				ShadowDialog.getLatestDialog());
	}

	private int color(int attr) {
		return UiUtils.resolveColorAttribute(feed, attr);
	}

	@Test
	public void aSelectedPostTintsItsCardAndDrawsNoOutline() throws Exception {
		open();
		longPress(0);

		View card = holder(0).itemView.findViewById(R.id.layout);
		int surface = color(R.attr.zerionColorSurface);
		assertEquals(surface, SelectedLook.painted(card, false));
		assertEquals(over(color(R.attr.zerionColorChannelCardSelected),
				surface), SelectedLook.painted(card));
		assertEquals(SelectedLook.strokeWidth(card, false),
				SelectedLook.strokeWidth(card, true));
		assertEquals(color(R.attr.zerionColorOutline),
				SelectedLook.strokeColor(card, true));
		assertNull(holder(0).itemView.getBackground());
		assertEquals(surface, SelectedLook.painted(
				holder(1).itemView.findViewById(R.id.layout)));
	}

	private List<String> unreadableWhenSelected(int night) throws Exception {
		ChannelPost.ChannelAttachment pdf = new ChannelPost.ChannelAttachment(
				new byte[32], 1_200_000L, "application/pdf", new byte[32],
				null);
		stub(false, post(1L, "Route: https://example.org/route"),
				post(2L, "We need drivers"),
				withFiles(3L, "The map for Saturday", pdf, pdf));
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(new org.zerionproject.app.api.channel
						.ChannelQueuedItem(9L, org.zerionproject.app.api.channel
						.ChannelQueuedItem.Kind.REACTION, 1L, "x", false,
						System.currentTimeMillis(), true)));
		open(night);
		longPress(0);
		holder(1).itemView.performClick();
		idle();
		holder(2).itemView.performClick();
		idle();
		assertEquals(View.VISIBLE, holder(0).itemView.findViewById(
				R.id.channelPostReactionsView).getVisibility());
		assertEquals(View.VISIBLE, holder(2).itemView.findViewById(
				R.id.channelPostAttachments).getVisibility());
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			assertTrue(SelectedLook.drawsSelected(holder(i).itemView));
			problems.addAll(SelectedLook.unreadable(holder(i).itemView, 4.5,
					4.5));
		}
		return problems;
	}

	@Test
	public void wordsOnASelectedPostStayReadableInLight() throws Exception {
		List<String> problems = unreadableWhenSelected(
				AppCompatDelegate.MODE_NIGHT_NO);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void wordsOnASelectedPostStayReadableInDark() throws Exception {
		List<String> problems = unreadableWhenSelected(
				AppCompatDelegate.MODE_NIGHT_YES);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theFilesReactionsAndPicturesOfASelectedCardTakeItsTint()
			throws Exception {
		ChannelPost.ChannelAttachment pdf = new ChannelPost.ChannelAttachment(
				new byte[32], 1_200_000L, "application/pdf", new byte[32],
				null);
		ChannelPost.ChannelAttachment photo =
				new ChannelPost.ChannelAttachment(new byte[32], 90_000L,
						"image/jpeg", new byte[32], null);
		stub(true, withFiles(1L, "Route for Saturday", pdf, photo),
				withFiles(2L, "We need drivers", pdf));
		open();
		longPress(0);

		int chip = color(R.attr.zerionColorSurfaceVariant);
		int selectedChip = over(color(R.attr.zerionColorChannelCardSelected),
				chip);
		int media = color(R.attr.zerionColorMessageSelectedOwn);
		for (int i = 0; i < 2; i++) {
			boolean selected = i == 0;
			View row = holder(i).itemView;
			android.view.ViewGroup files = row.findViewById(
					R.id.channelPostAttachments);
			List<View> chips = new ArrayList<>();
			for (int n = 0; n < files.getChildCount(); n++) {
				chips.add(files.getChildAt(n));
			}
			chips.add(row.findViewById(R.id.channelPostReactionsView));
			assertTrue(chips.size() > 1);
			for (View c : chips) {
				assertEquals(selected ? selectedChip : chip,
						SelectedLook.painted(c));
			}
			for (int n = 0; n < files.getChildCount(); n++) {
				android.widget.ImageView thumb = files.getChildAt(n)
						.findViewById(R.id.channelAttachmentThumb);
				int tint = androidx.core.widget.ImageViewCompat
						.getImageTintList(thumb).getColorForState(
								thumb.getDrawableState(), 0);
				assertEquals(selected ? media : media & 0xFFFFFF, tint);
			}
		}
	}

	@Test
	public void selectingAPostMovesNothing() throws Exception {
		open();
		List<String> before = SelectedLook.boxes(holder(0).itemView);

		longPress(0);

		assertTrue(SelectedLook.drawsSelected(holder(0).itemView));
		assertEquals(before, SelectedLook.boxes(holder(0).itemView));
	}

	@Test
	public void threeSelectedPostsAreEachToldAsSelected() throws Exception {
		stub(true, post(1L, "Route for Saturday"), post(2L, "We need drivers"),
				post(3L, "Bring water"), post(4L, "See you there"));
		open();
		longPress(0);
		holder(1).itemView.performClick();
		idle();
		holder(2).itemView.performClick();
		idle();

		assertEquals(3, bar().getCount());
		for (int i = 0; i < 3; i++) {
			View row = holder(i).itemView;
			assertTrue(SelectedLook.toldSelected(row));
			assertTrue(SelectedLook.toldSelected(
					row.findViewById(R.id.layout)));
			assertTrue(SelectedLook.drawsSelected(row));
		}
		assertFalse(SelectedLook.toldSelected(holder(3).itemView));
		assertFalse(SelectedLook.drawsSelected(holder(3).itemView));
	}

	private void longPress(int position) {
		assertTrue(holder(position).itemView.performLongClick());
		idle();
	}

	private SelectionBar bar() throws Exception {
		Field f = ChannelFeedActivity.class.getDeclaredField("selectionBar");
		f.setAccessible(true);
		return (SelectionBar) f.get(feed);
	}

	private static String message(AlertDialog d) {
		assertNotNull(d);
		TextView m = d.findViewById(android.R.id.message);
		assertNotNull(m);
		return m.getText().toString();
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
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
