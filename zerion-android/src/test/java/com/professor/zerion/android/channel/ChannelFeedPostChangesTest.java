package com.professor.zerion.android.channel;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
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
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelFeedPostChangesTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;

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

	@Before
	public void setUp() throws Exception {
		ProbeFeed.io.clear();
		byte[] id = new byte[32];
		ChannelState state = new ChannelState(id, new byte[16], new byte[32],
				new byte[0], "News", "", null, HOUR, true, null, "", 1L, false,
				1L);
		long now = System.currentTimeMillis() / HOUR * HOUR;
		ChannelPost quiet = new ChannelPost(id, 0L, new byte[32], now,
				"comments are off here", Collections.emptyList(), 0L,
				new byte[0], true);
		ChannelPost edited = new ChannelPost(id, 1L, new byte[32], now,
				"as first written", Collections.emptyList(), 0L,
				new byte[0], true).asEdited("as corrected");
		ChannelManager m = mock(ChannelManager.class);
		when(m.getChannel(any())).thenReturn(state);
		when(m.getRecentPosts(any(), anyLong()))
				.thenReturn(Arrays.asList(quiet, edited));
		when(m.areDiscussionsEnabled(any())).thenReturn(true);
		when(m.getCommentExceptions(any()))
				.thenReturn(Collections.singleton(0L));
		ProbeFeed.manager = m;
	}

	@Test
	public void theCommentEntryFollowsEachPostsSettingAndEditsAreMarked() {
		ProbeFeed feed = Robolectric.buildActivity(ProbeFeed.class,
				new Intent(ApplicationProvider.getApplicationContext(),
						ProbeFeed.class)).setup().get();
		runBackgroundWork();
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				4000, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 4000);

		View off = list.findViewHolderForAdapterPosition(0).itemView;
		View on = list.findViewHolderForAdapterPosition(1).itemView;
		assertEquals(View.GONE,
				off.findViewById(R.id.channelPostCommentBadge).getVisibility());
		assertEquals(View.VISIBLE,
				on.findViewById(R.id.channelPostCommentBadge).getVisibility());

		String edited = feed.getString(R.string.message_edited);
		assertTrue(text(on, R.id.channelPostTimestampView).contains(edited));
		assertFalse(text(off, R.id.channelPostTimestampView)
				.contains(edited));
		assertEquals("as corrected", text(on, R.id.channelPostBodyView));
	}

	private static String text(View row, int id) {
		return ((TextView) row.findViewById(id)).getText().toString();
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
