package com.professor.zerion.android.channel;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.view.TouchDelegate;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectionBar;
import com.professor.zerion.android.util.UiUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.core.api.event.EventBus;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

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
public class ChannelFeedCardTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;
	private static final Pattern HEX = Pattern.compile("[0-9a-f]{16}");

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
	private final byte[] editorKey = key((byte) 9);
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
		when(manager.getAnnouncedSubscribers(any())).thenReturn(
				Collections.singletonList(new ChannelSubscriber("Sam",
						editorKey, new byte[0], HOUR, false)));
	}

	@Before
	public void setUp() throws Exception {
		stub(true, post(1L, "Route for Saturday"), post(2L, "We need drivers"));
	}

	private ProbeFeed open() {
		Intent i = ChannelFeedActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId);
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeFeed.class);
		ProbeFeed f = Robolectric.buildActivity(ProbeFeed.class, i).setup()
				.get();
		feed = f;
		runIo();
		return f;
	}

	private static long hourNow() {
		return System.currentTimeMillis() / HOUR * HOUR;
	}

	private static ChannelPost post(long seq, String body) {
		return new ChannelPost(new byte[32], seq, new byte[32], hourNow(),
				body, Collections.emptyList(), 0L, new byte[64], true);
	}

	private ChannelPost editorPost(long seq, String body) {
		return new ChannelPost(new byte[32], seq, new byte[32], hourNow(),
				body, Collections.emptyList(), 0L, new byte[64], true,
				editorKey, new byte[0]);
	}

	private static ChannelPost timedPost(long seq, String body, long ttl,
			ChannelPost.ChannelAttachment... atts) {
		return new ChannelPost(new byte[32], seq, new byte[32], hourNow(),
				body, Arrays.asList(atts), ttl, new byte[64], true);
	}

	private static ChannelComment comment(long id) {
		return new ChannelComment(1L, id, "nice", "Alice", key((byte) 7),
				new byte[0], HOUR);
	}

	@Test
	public void anOwnersPostShowsTheChannelNameAndTheTime() throws Exception {
		open();

		View card = holder(0).itemView;
		assertEquals("News", text(card, R.id.channelPostAuthor));
		assertEquals("N", text(card, R.id.channelPostAvatar));
		assertEquals(View.GONE, card.findViewById(R.id.channelPostSignerBadge)
				.getVisibility());
		assertEquals(UiUtils.formatChannelHour(feed, hourNow()),
				text(card, R.id.channelPostTimestampView));
		assertEquals("Route for Saturday", text(card, R.id.channelPostBodyView));
		assertEquals(View.GONE, card.findViewById(R.id.channelPostTtlLabel)
				.getVisibility());
	}

	@Test
	public void anEditorsPostNamesTheEditorWithATag() throws Exception {
		stub(true, editorPost(1L, "We still need two drivers"));
		open();

		View card = holder(0).itemView;
		assertEquals("Sam", text(card, R.id.channelPostAuthor));
		assertEquals("S", text(card, R.id.channelPostAvatar));
		TextView badge = card.findViewById(R.id.channelPostSignerBadge);
		assertEquals(View.VISIBLE, badge.getVisibility());
		assertEquals("EDITOR", badge.getText().toString());
		assertFalse("the header shows no hex", HEX.matcher(
				text(card, R.id.channelPostAuthor)).find());
	}

	@Test
	public void aSubscriberWhoDoesNotKnowTheEditorSeesTheChannelWithTheTag()
			throws Exception {
		stub(false, editorPost(1L, "We still need two drivers"));
		open();

		View card = holder(0).itemView;
		assertEquals("a name only the owner was told is not shown", "News",
				text(card, R.id.channelPostAuthor));
		assertEquals(View.VISIBLE, card.findViewById(
				R.id.channelPostSignerBadge).getVisibility());
		assertFalse(HEX.matcher(text(card, R.id.channelPostAuthor)).find());
	}

	@Test
	public void anEditorsOwnPostStaysYouOncePublished() throws Exception {
		stub(false, new ChannelPost(new byte[32], 1L, new byte[32], hourNow(),
				"We still need two drivers", Collections.emptyList(), 0L,
				new byte[64], true, key((byte) 5), new byte[0]));
		open();

		View card = holder(0).itemView;
		assertEquals(feed.getString(R.string.channels_comment_you),
				text(card, R.id.channelPostAuthor));
		assertEquals(View.VISIBLE, card.findViewById(
				R.id.channelPostSignerBadge).getVisibility());
	}

	@Test
	public void aLongEditorNameKeepsTheTagOnTheCard() throws Exception {
		String name = "Samantha Alexandra Montgomery-Whitfields";
		assertEquals(40, name.length());
		stub(true, editorPost(1L, "We still need two drivers"));
		when(manager.getAnnouncedSubscribers(any())).thenReturn(
				Collections.singletonList(new ChannelSubscriber(name,
						editorKey, new byte[0], HOUR, false)));
		open();

		View card = holder(0, 360).itemView;
		TextView author = card.findViewById(R.id.channelPostAuthor);
		TextView badge = card.findViewById(R.id.channelPostSignerBadge);
		TextView time = card.findViewById(R.id.channelPostTimestampView);
		View header = card.findViewById(R.id.channelPostHeader);
		assertEquals(View.VISIBLE, badge.getVisibility());
		assertTrue("the tag has no room", badge.getWidth() > 0);
		assertTrue(author.getRight() <= badge.getLeft());
		assertTrue(badge.getRight() <= time.getLeft());
		assertTrue("the time " + time.getRight() + " falls off the header "
				+ header.getWidth(), time.getRight() <= header.getWidth());
		assertTrue(time.getWidth() > 0);

		int needed = Math.round(author.getPaint().measureText(name));
		int chrome = card.getWidth() - header.getWidth()
				+ card.findViewById(R.id.channelPostAvatar).getWidth()
				+ badge.getWidth() + time.getWidth() + 26;
		card = holder(0, chrome + needed / 2).itemView;
		author = card.findViewById(R.id.channelPostAuthor);
		badge = card.findViewById(R.id.channelPostSignerBadge);
		header = card.findViewById(R.id.channelPostHeader);
		assertEquals(View.VISIBLE, badge.getVisibility());
		assertTrue("the tag has no room", badge.getWidth() > 0);
		assertTrue("the tag " + badge.getRight() + " falls off the header "
				+ header.getWidth(), badge.getRight() <= header.getWidth());
		assertTrue("the name keeps its full width " + author.getWidth()
				+ " although only " + header.getWidth() + " fit",
				author.getWidth() > 0 && author.getWidth() < needed);
		assertTrue(author.getRight() <= badge.getLeft());
	}

	@Test
	public void tappingANotSentReactionWhileSelectingSelectsThePost()
			throws Exception {
		stub(false, post(1L, "Route for Saturday"), post(2L, "We need drivers"));
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(new org.zerionproject.app.api.channel
						.ChannelQueuedItem(9L, org.zerionproject.app.api.channel
						.ChannelQueuedItem.Kind.REACTION, 2L, "x", false,
						System.currentTimeMillis(), true)));
		open();
		View chip = holder(1).itemView.findViewById(
				R.id.channelPostReactionsView);
		assertEquals(View.VISIBLE, chip.getVisibility());
		assertTrue(chip.isClickable());

		assertTrue(holder(0).itemView.performLongClick());
		idle();
		chip.performClick();
		idle();

		assertEquals("the tap joins the selection", 2, bar().getCount());
		assertNull("no retry dialog opens while selecting",
				ShadowDialog.getLatestDialog());
	}

	@Test
	public void aNotSentReactionIsAtLeast48dpToTouch() throws Exception {
		stub(false, post(1L, "Route for Saturday"), post(2L, "We need drivers"));
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(new org.zerionproject.app.api.channel
						.ChannelQueuedItem(9L, org.zerionproject.app.api.channel
						.ChannelQueuedItem.Kind.REACTION, 2L, "x", false,
						System.currentTimeMillis(), true)));
		open();
		View card = holder(1).itemView.findViewById(R.id.layout);
		View chip = card.findViewById(R.id.channelPostReactionsView);
		assertTrue(chip.isClickable());
		float density = feed.getResources().getDisplayMetrics().density;
		int min = Math.round(48 * density);

		TouchDelegate delegate = card.getTouchDelegate();
		assertNotNull("the chip has a wider target", delegate);
		Field bounds = TouchDelegate.class.getDeclaredField("mBounds");
		bounds.setAccessible(true);
		Rect target = (Rect) bounds.get(delegate);
		assertTrue("target " + target.width() / density + "x"
						+ target.height() / density + "dp",
				target.width() >= min && target.height() >= min);
		Rect drawn = new Rect();
		chip.getHitRect(drawn);
		assertTrue("the target covers the chip", target.contains(drawn));
		assertTrue("the target stays on the card", target.left >= 0
				&& target.right <= card.getWidth()
				&& target.bottom <= card.getHeight());
		assertNull("a card without a waiting reaction keeps its own touches",
				holder(0).itemView.findViewById(R.id.layout)
						.getTouchDelegate());
	}

	@Test
	public void aChipThatCanNoLongerBeTappedDoesNotGetItsWiderTargetBack()
			throws Exception {
		stub(false, post(1L, "Route for Saturday"), post(2L, "We need drivers"));
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(new org.zerionproject.app.api.channel
						.ChannelQueuedItem(9L, org.zerionproject.app.api.channel
						.ChannelQueuedItem.Kind.REACTION, 2L, "x", false,
						System.currentTimeMillis(), true)));
		open();
		View card = holder(1).itemView.findViewById(R.id.layout);
		View chip = card.findViewById(R.id.channelPostReactionsView);
		assertNotNull(card.getTouchDelegate());

		chip.setOnClickListener(null);
		chip.setClickable(false);
		com.professor.zerion.android.accessibility.TouchTargets.drop(chip);
		chip.layout(chip.getLeft(), chip.getTop(), chip.getRight() + 10,
				chip.getBottom());
		assertNull("a chip that cannot be tapped got its wider target back",
				card.getTouchDelegate());
	}

	@Test
	public void theWordsOfACardAreNotAStopOfTheirOwn() throws Exception {
		open();
		View row = holder(0).itemView;
		TextView body = row.findViewById(R.id.channelPostBodyView);

		AccessibilityNodeInfo words = body.createAccessibilityNodeInfo();
		assertFalse("the words are not a stop of their own",
				words.isLongClickable() || words.isClickable()
						|| words.isFocusable());
		assertTrue("the card reads once",
				row.createAccessibilityNodeInfo().isScreenReaderFocusable());
		assertNotNull(row.findViewById(R.id.layout).getContentDescription());
	}

	@Test
	public void theCommentsButtonCountsAndOpensTheComments() throws Exception {
		ChannelPost.ChannelAttachment pdf = new ChannelPost.ChannelAttachment(
				new byte[32], 1_200_000L, "application/pdf", new byte[32],
				null);
		stub(true, timedPost(1L, "Route for Saturday", 6 * DAY, pdf));
		when(manager.getAllComments(any())).thenReturn(Arrays.asList(
				comment(1L), comment(2L), comment(3L), comment(4L)));
		open();

		View card = holder(0).itemView;
		TextView comments = card.findViewById(R.id.channelPostCommentBadge);
		assertEquals(View.VISIBLE, comments.getVisibility());
		assertEquals(feed.getString(R.string.channels_post_comments_count, 4),
				comments.getText().toString());
		comments.performClick();
		idle();

		Intent next = commentsOpened();
		assertNotNull(next);
		assertEquals(1L, next.getLongExtra(
				"com.professor.zerion.android.channel.COMMENTS_PARENT_SEQ",
				-1L));
		assertEquals("the comments know when the post disappears",
				hourNow() + 6 * DAY, next.getLongExtra(
						ChannelCommentsActivity.EXTRA_EXPIRES_AT, 0L));
	}

	@Test
	public void theReactButtonOpensThePickerUnlessSelecting()
			throws Exception {
		open();

		View react = holder(0).itemView.findViewById(
				R.id.channelPostReactButton);
		assertEquals(View.VISIBLE, react.getVisibility());
		assertEquals(feed.getString(R.string.action_react),
				((TextView) react).getText().toString());
		react.performClick();
		idle();
		assertNotNull("the reaction picker opens",
				ShadowDialog.getLatestDialog());
		ShadowDialog.getLatestDialog().dismiss();
		ShadowDialog.reset();

		assertTrue(holder(1).itemView.performLongClick());
		idle();
		holder(0).itemView.findViewById(R.id.channelPostReactButton)
				.performClick();
		idle();
		assertEquals("the tap joins the selection instead", 2,
				bar().getCount());
		assertNull(ShadowDialog.getLatestDialog());
	}

	@Test
	public void aTimedPostSaysWhenItDisappears() throws Exception {
		stub(true, timedPost(1L, "Route for Saturday", 6 * DAY));
		open();

		TextView ttl = holder(0).itemView.findViewById(
				R.id.channelPostTtlLabel);
		assertEquals(View.VISIBLE, ttl.getVisibility());
		String shown = ttl.getText().toString();
		assertTrue(shown, shown.startsWith(feed.getString(
				R.string.channels_post_disappears, "").trim()));
		assertTrue(shown, shown.contains("6 days") || shown.contains("5 days"));
	}

	@Test
	public void theCardReadsAsOneDescription() throws Exception {
		ChannelPost.ChannelAttachment pdf = new ChannelPost.ChannelAttachment(
				new byte[32], 1_200_000L, "application/pdf", new byte[32],
				null);
		stub(true, timedPost(1L, "Route for Saturday", 0L, pdf));
		when(manager.getAllComments(any())).thenReturn(Arrays.asList(
				comment(1L), comment(2L)));
		open();

		View card = holder(0).itemView.findViewById(R.id.layout);
		CharSequence said = card.getContentDescription();
		assertNotNull(said);
		String s = said.toString();
		assertTrue(s, s.startsWith("News"));
		assertTrue(s, s.contains(UiUtils.formatChannelHour(feed, hourNow())));
		assertTrue(s, s.contains("Route for Saturday"));
		assertTrue(s, s.contains("PDF document"));
		assertTrue(s, s.contains(feed.getString(
				R.string.channels_post_comments_count, 2)));
		assertFalse(s, HEX.matcher(s).find());
		View header = holder(0).itemView.findViewById(R.id.channelPostHeader);
		assertEquals("the header is not read a second time",
				View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
				header.getImportantForAccessibility());
		View row = ((android.view.ViewGroup) holder(0).itemView.findViewById(
				R.id.channelPostAttachments)).getChildAt(0);
		assertEquals("PDF document", ((TextView) row.findViewById(
				R.id.channelAttachmentMime)).getText().toString());
	}

	@Test
	public void aCardSaysTheHourAsAnHourNotAMinute() throws Exception {
		open();

		String shown = text(holder(0).itemView,
				R.id.channelPostTimestampView);
		assertTrue(shown, shown.startsWith("around "));
		assertEquals(UiUtils.formatChannelHour(feed, hourNow()), shown);
	}

	@Test
	public void aSubscriberHearsAnEditorsPostAsTheEditorRoleAndNoName()
			throws Exception {
		stub(false, editorPost(1L, "We still need two drivers"));
		when(manager.getAllComments(any())).thenReturn(Collections
				.singletonList(new ChannelComment(1L, 1L, "I can drive",
						"Sam", editorKey, new byte[0], HOUR)));
		open();

		View card = holder(0).itemView;
		String editor = feed.getString(R.string.channels_post_signer_editor);
		assertEquals("News", text(card, R.id.channelPostAuthor));
		assertEquals(editor.toUpperCase(java.util.Locale.ROOT),
				text(card, R.id.channelPostSignerBadge));
		CharSequence said = card.findViewById(R.id.layout)
				.getContentDescription();
		assertNotNull(said);
		String s = said.toString();
		assertTrue(s, s.contains(editor));
		assertFalse("a name the post does not carry is read out: " + s,
				s.contains("Sam"));
		assertFalse(s, HEX.matcher(s).find());
	}

	@Test
	public void aReceivedCardNamesTheKindNotTheSendersFileName()
			throws Exception {
		ChannelPost.ChannelAttachment pdf = new ChannelPost.ChannelAttachment(
				new byte[32], 1_200_000L, "application/pdf", new byte[32],
				"../../Dana tax return 2026 \u202Efdp.exe");
		stub(false, timedPost(1L, "", 0L, pdf));
		open();

		View card = holder(0).itemView;
		View row = ((android.view.ViewGroup) card.findViewById(
				R.id.channelPostAttachments)).getChildAt(0);
		assertEquals(feed.getString(R.string.attachment_kind_pdf),
				((TextView) row.findViewById(R.id.channelAttachmentMime))
						.getText().toString());
		StringBuilder shown = new StringBuilder();
		collectText(card, shown);
		shown.append(' ').append(card.findViewById(R.id.layout)
				.getContentDescription());
		shown.append(' ').append(row.getContentDescription());
		assertFalse(shown.toString(), shown.toString().contains("Dana"));
		assertFalse(shown.toString(), shown.toString().contains("tax"));
		assertFalse(shown.toString(), shown.toString().contains(".."));
	}

	private static void collectText(View v, StringBuilder out) {
		if (v instanceof TextView) {
			out.append(((TextView) v).getText()).append(' ');
		}
		if (v instanceof android.view.ViewGroup) {
			android.view.ViewGroup g = (android.view.ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collectText(g.getChildAt(i), out);
			}
		}
	}

	@Test
	public void aSubscriberIsToldWhoPostsHere() throws Exception {
		stub(false, post(1L, "Route for Saturday"));
		open();

		TextView footer = feed.findViewById(R.id.channelFeedReadOnlyFooter);
		assertEquals(View.VISIBLE, footer.getVisibility());
		assertEquals(feed.getString(R.string.channels_feed_read_only_footer),
				footer.getText().toString());
		assertEquals(View.GONE, feed.findViewById(R.id.channelComposeBar)
				.getVisibility());
	}

	@Test
	public void theOwnerSeesTheComposerNotTheFooter() throws Exception {
		open();

		assertEquals(View.GONE, feed.findViewById(
				R.id.channelFeedReadOnlyFooter).getVisibility());
		assertEquals(View.VISIBLE, feed.findViewById(R.id.channelComposeBar)
				.getVisibility());
	}

	@Test
	public void aNewCommentRebindsOnlyItsCard() throws Exception {
		open();
		int[] whole = {0};
		List<Integer> changed = new ArrayList<>();
		adapter().registerAdapterDataObserver(
				new RecyclerView.AdapterDataObserver() {
					@Override
					public void onChanged() {
						whole[0]++;
					}

					@Override
					public void onItemRangeChanged(int start, int count) {
						for (int i = 0; i < count; i++) changed.add(start + i);
					}
				});
		when(manager.getAllComments(any())).thenReturn(
				Collections.singletonList(comment(1L)));

		feed.eventOccurred(new ChannelCommentReceivedEvent(channelId, 1L));
		runIo();

		assertEquals("the whole list was rebound", 0, whole[0]);
		assertEquals(Collections.singletonList(0), changed);
		assertEquals(feed.getString(R.string.channels_post_comments_count, 1),
				text(holder(0).itemView, R.id.channelPostCommentBadge));
		assertEquals(feed.getString(R.string.channels_action_comments),
				text(holder(1).itemView, R.id.channelPostCommentBadge));
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

	private SelectionBar bar() throws Exception {
		Field f = ChannelFeedActivity.class.getDeclaredField("selectionBar");
		f.setAccessible(true);
		return (SelectionBar) f.get(feed);
	}

	private static String text(View root, int id) {
		return ((TextView) root.findViewById(id)).getText().toString();
	}

	private RecyclerView.Adapter<?> adapter() {
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
		return list.getAdapter();
	}

	private RecyclerView.ViewHolder holder(int position) {
		return holder(position, 1080);
	}

	private RecyclerView.ViewHolder holder(int position, int width) {
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(width,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, width, 1920);
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
