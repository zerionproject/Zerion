package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSyncStatus;
import org.zerionproject.core.api.event.EventBus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelSyncAnnouncementTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long MINUTE = 60L * 1000L;

	public static class ProbeFeed extends ChannelFeedActivity {

		static final List<Runnable> io = new ArrayList<>();
		static ChannelManager manager;

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
	private ProbeFeed feed;
	private ShadowAccessibilityManager talkBack;

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
		when(manager.getChannel(any())).thenReturn(new ChannelState(id,
				new byte[16], new byte[32], new byte[0], "News", "", null,
				HOUR, true, null, "", 1L, false, 0L));
		ProbeFeed.manager = manager;
	}

	private void status(long at, boolean failed) throws Exception {
		when(manager.getSyncStatus(any()))
				.thenReturn(new ChannelSyncStatus(at, failed, false));
	}

	private void open() {
		feed = Robolectric.buildActivity(ProbeFeed.class,
				new Intent(ctx, ProbeFeed.class)).setup().get();
		talkBack = shadowOf((AccessibilityManager) feed.getSystemService(
				Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		runBackgroundWork();
	}

	private void reload() throws Exception {
		Method m = ChannelFeedActivity.class.getDeclaredMethod("loadChannel");
		m.setAccessible(true);
		m.invoke(feed);
		runBackgroundWork();
	}

	private List<String> said() {
		shadowOf(Looper.getMainLooper()).idle();
		List<String> out = new ArrayList<>();
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
				out.add(e.getText().toString());
			}
		}
		return out;
	}

	@Test
	public void catchingUpIsSaidOnceAndNotEveryMinuteAfter()
			throws Exception {
		long start = System.currentTimeMillis() - 10 * MINUTE;
		status(start, true);
		open();
		assertTrue("opening is quiet: " + said(), said().isEmpty());

		status(System.currentTimeMillis() - 5 * MINUTE, false);
		reload();
		List<String> said = said();
		assertEquals(said.toString(), 1, said.size());
		String updated = ctx.getString(R.string.channels_sync_last_updated,
				"");
		assertTrue(said.get(0), said.get(0).contains(updated.trim()));

		status(System.currentTimeMillis() - 2 * MINUTE, false);
		reload();
		status(System.currentTimeMillis(), false);
		reload();
		reload();
		assertEquals("a newer time is not news: " + said(), 1,
				said().size());

		status(System.currentTimeMillis(), true);
		reload();
		assertEquals(2, said().size());
		assertTrue(said().get(1), said().get(1).contains(ctx.getString(
				R.string.channels_sync_saved_posts)));
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
