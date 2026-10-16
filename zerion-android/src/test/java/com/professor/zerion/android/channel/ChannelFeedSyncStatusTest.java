package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSyncStatus;
import org.zerionproject.core.api.event.EventBus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelFeedSyncStatusTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;

	public static class ProbeFeed extends ChannelFeedActivity {

		static final List<Runnable> io = new ArrayList<>();
		static ChannelManager manager = mock(ChannelManager.class);

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			eventBus = mock(EventBus.class);
			notificationManager = mock(AndroidNotificationManager.class);
			ioExecutor = io::add;
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final byte[] id = new byte[32];
	private ChannelManager manager;

	@Before
	public void setUp() throws Exception {
		ProbeFeed.io.clear();
		long now = System.currentTimeMillis() / HOUR * HOUR;
		ChannelPost post = new ChannelPost(id, 0L, new byte[32], now,
				"saved post", Collections.emptyList(), 0L, new byte[0], true);
		manager = mock(ChannelManager.class);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.singletonList(post));
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.getCommentExceptions(any()))
				.thenReturn(Collections.emptySet());
		ProbeFeed.manager = manager;
	}

	private void subscribed(boolean owner, ChannelSyncStatus status)
			throws Exception {
		ChannelState state = new ChannelState(id, new byte[16],
				new byte[32], new byte[0], "News", "", null, HOUR, true, null,
				"", 1L, owner, 0L);
		when(manager.getChannel(any())).thenReturn(state);
		when(manager.getSyncStatus(any())).thenReturn(status);
	}

	private ProbeFeed open() {
		ProbeFeed feed = Robolectric.buildActivity(ProbeFeed.class,
				new Intent(ctx, ProbeFeed.class)).setup().get();
		runBackgroundWork();
		return feed;
	}

	private static TextView line(ProbeFeed feed) {
		return feed.findViewById(R.id.channelSyncStatusLine);
	}

	private static String date(Context ctx, long at) {
		return DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_DATE
				| DateUtils.FORMAT_ABBREV_MONTH);
	}

	private static void assertQuiet(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (String word : new String[] {"owner", "online", "reachable",
				"onion", "epoch", "checkpoint", "manifest"}) {
			assertFalse(text, lower.contains(word));
		}
	}

	@Test
	public void heldPostsShowWhenTheyWereLastUpdated() throws Exception {
		long at = System.currentTimeMillis();
		subscribed(false, new ChannelSyncStatus(at, false, false));
		TextView line = line(open());
		assertEquals(View.VISIBLE, line.getVisibility());
		assertEquals(ctx.getString(R.string.channels_sync_last_updated,
				DateUtils.formatDateTime(ctx, at,
						DateUtils.FORMAT_SHOW_TIME)),
				line.getText().toString());
		assertQuiet(line.getText().toString());
	}

	@Test
	public void aFailedAttemptSaysSavedPostsAndTheDay() throws Exception {
		long at = System.currentTimeMillis() - 3L * DAY;
		subscribed(false, new ChannelSyncStatus(at, true, false));
		TextView line = line(open());
		assertEquals(View.VISIBLE, line.getVisibility());
		assertEquals(ctx.getString(
				R.string.channels_sync_saved_posts_last_updated,
				date(ctx, at)), line.getText().toString());
		assertQuiet(line.getText().toString());
	}

	@Test
	public void aDormantChannelIsTriedOnceWhenOpened() throws Exception {
		long at = System.currentTimeMillis() - 100L * DAY;
		subscribed(false, new ChannelSyncStatus(at, true, true));
		ProbeFeed feed = open();
		assertEquals(ctx.getString(R.string.channels_sync_not_updated_since,
				date(ctx, at)), line(feed).getText().toString());
		assertQuiet(line(feed).getText().toString());
		for (int i = 0; i < 4; i++) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
			runBackgroundWork();
		}
		verify(manager, times(1)).refreshChannelOnOpen(any());
		verify(manager, never()).refreshChannel(any());
		verify(manager, atLeast(3)).refreshChannelTick(any());
	}

	@Test
	public void onlyOpeningTheFeedRestartsTheBackoff() throws Exception {
		subscribed(false, new ChannelSyncStatus(System.currentTimeMillis(),
				true, false));
		when(manager.refreshChannelTick(any())).thenReturn(true);
		open();
		for (int i = 0; i < 4; i++) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
			runBackgroundWork();
		}
		verify(manager, times(1)).refreshChannelOnOpen(any());
		verify(manager, atLeast(3)).refreshChannelTick(any());
		verify(manager, never()).refreshChannel(any());
	}

	@Test
	public void theSlowConnectingHintNamesNoOne() {
		String hint = ctx.getString(R.string.channels_feed_connecting_slow);
		String lower = hint.toLowerCase(Locale.ROOT);
		for (String word : new String[] {"person", "shared", "online",
				"owner", "make sure"}) {
			assertFalse(hint, lower.contains(word));
		}
		assertQuiet(hint);
	}

	@Test
	public void theOwnersOwnChannelShowsNoStatus() throws Exception {
		subscribed(true, new ChannelSyncStatus(System.currentTimeMillis(),
				true, false));
		assertEquals(View.GONE, line(open()).getVisibility());
	}

	private static void runBackgroundWork() {
		for (int round = 0; round < 4 && !ProbeFeed.io.isEmpty(); round++) {
			List<Runnable> pending = new ArrayList<>(ProbeFeed.io);
			ProbeFeed.io.clear();
			for (Runnable r : pending) {
				try {
					r.run();
				} catch (RuntimeException ignored) {
				}
			}
			shadowOf(Looper.getMainLooper()).idle();
		}
	}
}
