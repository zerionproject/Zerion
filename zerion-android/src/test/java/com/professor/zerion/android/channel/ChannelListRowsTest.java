package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.ListAdapter;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.homelist.HomeListProbes;
import com.professor.zerion.android.profile.TestProfileSession;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.homelist.HomeListProbes.rows;
import static com.professor.zerion.android.homelist.HomeListProbes.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelListRowsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager;

	public static class ProbeList extends ChannelListFragment {

		@Override
		public void injectFragment(ActivityComponent component) {
			channelManager = manager;
			eventBus = mock(EventBus.class);
			ioExecutor = io::add;
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final byte[] news = id((byte) 1);
	private final byte[] club = id((byte) 2);
	private final long hour = System.currentTimeMillis() / HOUR * HOUR;

	@Before
	public void setUp() throws Exception {
		io.clear();
		manager = mock(ChannelManager.class);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.<ChannelPost>emptyList());
		when(manager.getOwnerDeletedChannelNames())
				.thenReturn(Collections.<String>emptyList());
	}

	@After
	public void tearDown() {
		TestProfileSession.signedInPreferences(ctx).edit()
				.remove("mute_channel_" + StringUtils.toHexString(news))
				.commit();
	}

	private static byte[] id(byte b) {
		byte[] id = new byte[32];
		Arrays.fill(id, b);
		return id;
	}

	private static ChannelState channel(byte[] id, String name,
			boolean owner, boolean approval) {
		return new ChannelState(id, new byte[16], new byte[32], new byte[0],
				name, "", null, HOUR, false, new byte[32], "", 1L, owner, 0L,
				null, null, Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, approval);
	}

	private void channels(ChannelState... states) throws Exception {
		when(manager.getChannels()).thenReturn(Arrays.asList(states));
	}

	private void post(byte[] id, long when, String body, boolean withheld)
			throws Exception {
		ChannelPost p = new ChannelPost(id, 3L, new byte[32], when, body,
				Collections.<ChannelPost.ChannelAttachment>emptyList(), 0L,
				new byte[0], false, null, null, withheld);
		when(manager.getRecentPosts(eq(id), eq(1L)))
				.thenReturn(Collections.singletonList(p));
	}

	private ProbeList open() {
		ProbeList list = new ProbeList();
		HomeListProbes.host(list, io);
		return list;
	}

	private View onlyRow(ProbeList list) {
		List<View> rows = rows(list);
		assertEquals(1, rows.size());
		return rows.get(0);
	}

	@Test
	public void theTimeNoLongerSitsInTheChannelName() throws Exception {
		channels(channel(news, "News", false, false));
		post(news, hour, "We still need two drivers", false);
		View row = onlyRow(open());
		assertEquals("News", text(row, R.id.homeRowName));
		assertFalse(text(row, R.id.homeRowName).contains("•"));
		assertFalse(text(row, R.id.homeRowTime).isEmpty());
		assertEquals("We still need two drivers",
				text(row, R.id.homeRowPreview));
	}

	@Test
	public void aPostInTheMidnightHourShowsTheDayNotMidnight()
			throws Exception {
		java.util.Calendar c = java.util.Calendar.getInstance();
		c.set(java.util.Calendar.HOUR_OF_DAY, 0);
		c.set(java.util.Calendar.MINUTE, 0);
		c.set(java.util.Calendar.SECOND, 0);
		c.set(java.util.Calendar.MILLISECOND, 0);
		long midnight = c.getTimeInMillis();
		channels(channel(news, "News", false, false));
		post(news, midnight, "Market opens at nine", false);
		String shown = text(onlyRow(open()), R.id.homeRowTime);
		assertFalse(shown, shown.contains(":"));
		assertEquals(android.text.format.DateUtils.formatDateTime(ctx,
				midnight, android.text.format.DateUtils.FORMAT_SHOW_DATE
						| android.text.format.DateUtils.FORMAT_NO_YEAR
						| android.text.format.DateUtils.FORMAT_ABBREV_MONTH),
				shown);
	}

	@Test
	public void theOwnersOwnPostsSayYou() throws Exception {
		channels(channel(news, "News", true, false));
		post(news, hour, "Market opens at nine", false);
		assertEquals("You: Market opens at nine",
				text(onlyRow(open()), R.id.homeRowPreview));
	}

	@Test
	public void withoutPostsTheRowSaysWhoMayRead() throws Exception {
		channels(channel(news, "News", false, true));
		View row = onlyRow(open());
		assertEquals("Private channel · Approval required",
				text(row, R.id.homeRowPreview));
		assertEquals(View.GONE, row.findViewById(R.id.homeRowTime)
				.getVisibility());
	}

	@Test
	public void aWithheldPostIsNotPreviewed() throws Exception {
		channels(channel(news, "News", false, false));
		post(news, hour, "text the owner took back", true);
		String preview = text(onlyRow(open()), R.id.homeRowPreview);
		assertFalse(preview, preview.contains("took back"));
		assertTrue(preview, preview.startsWith("Post withheld"));
	}

	private void postWithTimer(byte[] id, long when, String body, long ttl)
			throws Exception {
		ChannelPost p = new ChannelPost(id, 3L, new byte[32], when, body,
				Collections.<ChannelPost.ChannelAttachment>emptyList(), ttl,
				new byte[0], false);
		when(manager.getRecentPosts(eq(id), eq(1L)))
				.thenReturn(Collections.singletonList(p));
	}

	@Test
	@Config(qualifiers = "de")
	public void aDeletedPostIsShownInTheReadersLanguage() throws Exception {
		channels(channel(news, "News", false, false));
		post(news, hour, ChannelConstants.DELETED_POST_PLACEHOLDER, false);
		ProbeList list = open();
		String preview = text(onlyRow(list), R.id.homeRowPreview);
		assertEquals(list.getString(R.string.channels_post_deleted), preview);
		assertEquals("[gelöscht]", preview);
	}

	@Test
	public void anExpiredPostIsNotPreviewed() throws Exception {
		channels(channel(news, "News", false, false));
		postWithTimer(news, hour - 3 * HOUR, "an old secret", HOUR);
		String preview = text(onlyRow(open()), R.id.homeRowPreview);
		assertFalse(preview, preview.contains("secret"));
		assertEquals("Private channel", preview);
	}

	@Test
	public void aPostWithATimerLeavesThePreviewWhenItExpires()
			throws Exception {
		channels(channel(news, "News", false, false));
		long wall = System.currentTimeMillis();
		postWithTimer(news, wall, "brief", 5_000L);
		ProbeList list = open();
		assertEquals("brief", text(onlyRow(list), R.id.homeRowPreview));
		when(manager.getRecentPosts(eq(news), eq(1L)))
				.thenReturn(Collections.<ChannelPost>emptyList());
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7));
		HomeListProbes.run(io);
		assertEquals("Private channel",
				text(onlyRow(list), R.id.homeRowPreview));
	}

	@Test
	public void aMutedChannelShowsTheMutedIcon() throws Exception {
		channels(channel(news, "News", false, false));
		assertTrue(TestProfileSession.signedInPreferences(ctx).edit()
				.putBoolean("mute_channel_" + StringUtils.toHexString(news),
						true).commit());
		View row = onlyRow(open());
		assertEquals(View.VISIBLE, row.findViewById(R.id.homeRowMuted)
				.getVisibility());
		assertTrue(String.valueOf(row.getContentDescription())
				.endsWith("Muted"));
	}

	@Test
	public void theUnreadBadgeStopsAt99() throws Exception {
		channels(channel(news, "News", false, false));
		when(manager.getUnreadCount(any())).thenReturn(120);
		assertEquals("99+", text(onlyRow(open()), R.id.homeRowUnread));
	}

	@Test
	public void theNewestChannelComesFirst() throws Exception {
		channels(channel(club, "Club", false, false),
				channel(news, "News", false, false));
		post(club, hour - 5 * HOUR, "older", false);
		post(news, hour, "newer", false);
		List<View> rows = rows(open());
		assertEquals(2, rows.size());
		assertEquals("News", text(rows.get(0), R.id.homeRowName));
		assertEquals("Club", text(rows.get(1), R.id.homeRowName));
	}

	@Test
	public void tappingARowOpensTheChannel() throws Exception {
		channels(channel(news, "News", false, false));
		ProbeList list = open();
		onlyRow(list).performClick();
		Intent next = shadowOf(list.requireActivity())
				.getNextStartedActivity();
		assertNotNull(next);
		assertEquals(ChannelFeedActivity.class.getName(),
				next.getComponent().getClassName());
	}

	@Test
	public void holdingARowStillOpensTheChannelMenu() throws Exception {
		channels(channel(news, "News", false, false));
		ProbeList list = open();
		assertTrue(onlyRow(list).performLongClick());
		HomeListProbes.run(io);
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull("no channel menu", d);
		assertEquals("News", HomeListProbes.dialogTitle(d));
		ListAdapter items = ((AlertDialog) d).getListView().getAdapter();
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < items.getCount(); i++) {
			labels.add(String.valueOf(items.getItem(i)));
		}
		assertTrue(labels.toString(), labels.contains(
				list.getString(R.string.channels_action_share_invite)));
		assertTrue(labels.toString(), labels.contains(
				list.getString(R.string.channels_settings_title)));
	}

	@Test
	public void ownerDeletedNoticesCanStillBeDismissed() throws Exception {
		channels(channel(news, "News", false, false));
		when(manager.getOwnerDeletedChannelNames()).thenReturn(
				new ArrayList<>(Collections.singletonList("Old news")));
		ProbeList list = open();
		View notice = HomeListProbes.child(list, 0);
		assertEquals(R.id.homeNotice, notice.getId());
		assertEquals(list.getString(R.string.channels_owner_deleted_notice,
				"Old news"), text(notice, R.id.homeNoticeText));
		when(manager.getOwnerDeletedChannelNames())
				.thenReturn(Collections.<String>emptyList());
		notice.findViewById(R.id.homeNoticeClose).performClick();
		HomeListProbes.run(io);
		verify(manager).dismissOwnerDeletedChannelName("Old news");
		assertEquals(R.id.homeRow, HomeListProbes.child(list, 0).getId());
		assertEquals(1, HomeListProbes.list(list).getChildCount());
	}

	@Test
	public void twoDeletedChannelsWithOneNameBothKeepANotice()
			throws Exception {
		channels(channel(news, "News", false, false));
		when(manager.getOwnerDeletedChannelNames()).thenReturn(
				Arrays.asList("Old news", "Old news"));
		ProbeList list = open();
		RecyclerView view =
				HomeListProbes.list(list);
		assertEquals(3, view.getChildCount());
		assertEquals(R.id.homeNotice, view.getChildAt(0).getId());
		assertEquals(R.id.homeNotice, view.getChildAt(1).getId());
		RecyclerView.Adapter<?> adapter =
				view.getAdapter();
		assertNotNull(adapter);
		assertTrue(adapter.getItemId(0) != adapter.getItemId(1));
	}

	@Test
	public void noticesStayAboveTheEmptyState() throws Exception {
		channels();
		when(manager.getOwnerDeletedChannelNames()).thenReturn(
				Arrays.asList("One", "Two", "Three"));
		ProbeList list = open();
		RecyclerView view = HomeListProbes.list(list);
		assertEquals(4, view.getChildCount());
		for (int i = 0; i < 3; i++) {
			assertEquals(R.id.homeNotice, view.getChildAt(i).getId());
		}
		View empty = view.getChildAt(3);
		assertEquals(R.id.homeEmpty, empty.getId());
		assertTrue(empty.getTop() >= view.getChildAt(2).getBottom());
	}

	@Test
	public void aFailedLoadKeepsTheNoticesAndOffersToTryAgain()
			throws Exception {
		when(manager.getOwnerDeletedChannelNames()).thenReturn(
				Collections.singletonList("Old news"));
		when(manager.getChannels()).thenThrow(new DbException());
		ProbeList list = open();
		RecyclerView view = HomeListProbes.list(list);
		assertEquals(R.id.homeNotice, view.getChildAt(0).getId());
		View state = HomeListProbes.empty(list);
		assertNotNull(state);
		assertEquals("This list could not be loaded.",
				text(state, R.id.homeEmptyTitle));
		doReturn(Collections.singletonList(channel(news, "News", false, false)))
				.when(manager).getChannels();
		state.findViewById(R.id.homeEmptyRetry).performClick();
		HomeListProbes.run(io);
		assertEquals("News", text(onlyRow(list), R.id.homeRowName));
	}

	@Test
	public void anEmptyListShowsTheSharedEmptyState() throws Exception {
		channels();
		ProbeList list = open();
		View empty = HomeListProbes.empty(list);
		assertEquals(View.VISIBLE, empty.getVisibility());
		assertEquals(list.getString(R.string.channels_list_empty),
				text(empty, R.id.homeEmptyTitle));
		assertEquals(View.GONE, empty.findViewById(R.id.homeEmptyBody)
				.getVisibility());
	}
}
