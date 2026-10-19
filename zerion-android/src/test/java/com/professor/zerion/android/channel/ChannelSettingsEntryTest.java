package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.widget.ListAdapter;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelSettingsEntryTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String SETTINGS =
			"com.professor.zerion.android.channel.ChannelSettingsActivity";

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager = mock(ChannelManager.class);

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

	public static class ProbeList extends ChannelListFragment {

		@Override
		public void injectFragment(ActivityComponent component) {
			channelManager = manager;
			eventBus = mock(EventBus.class);
			ioExecutor = io::add;
		}
	}

	public static class ProbeHost extends FragmentActivity
			implements BaseFragment.BaseFragmentListener {

		@Override
		public void runOnDbThread(Runnable runnable) {
		}

		@Override
		public ActivityComponent getActivityComponent() {
			return null;
		}

		@Override
		public void showNextFragment(BaseFragment f) {
		}

		@Override
		public void handleException(Exception e) {
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final byte[] id = new byte[32];

	@Before
	public void setUp() throws Exception {
		io.clear();
		ShadowDialog.reset();
		manager = mock(ChannelManager.class);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		when(manager.getOwnerDeletedChannelNames())
				.thenReturn(Collections.emptyList());
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.getCommentExceptions(any()))
				.thenReturn(Collections.emptySet());
		ChannelState s = new ChannelState(id, new byte[16], new byte[32],
				new byte[0], "News", "", null, 3_600_000L, false,
				new byte[32], "", 1L, true, 0L, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, true);
		when(manager.getChannel(any())).thenReturn(s);
		when(manager.getChannels()).thenReturn(Collections.singletonList(s));
	}

	private ProbeFeed openFeed() {
		Intent i = new Intent(ctx, ProbeFeed.class);
		i.putExtra(ChannelFeedActivity.EXTRA_CHANNEL_ID, id);
		ProbeFeed feed = Robolectric.buildActivity(ProbeFeed.class, i)
				.setup().get();
		runBackgroundWork();
		return feed;
	}

	@Test
	public void theFeedMenuIsShortAndOpensTheSettings() {
		ProbeFeed feed = openFeed();
		Menu menu = shadowOf(feed).getOptionsMenu();
		assertNotNull(menu);
		feed.onPrepareOptionsMenu(menu);
		List<String> titles = new ArrayList<>();
		for (int i = 0; i < menu.size(); i++) {
			if (menu.getItem(i).isVisible()) {
				titles.add(String.valueOf(menu.getItem(i).getTitle()));
			}
		}
		assertEquals(Arrays.asList("Channel settings", "Share invite link",
				"Mute notifications"), titles);
		feed.onOptionsItemSelected(menu.getItem(0));
		assertEquals(SETTINGS, shadowOf(feed).getNextStartedActivity()
				.getComponent().getClassName());
	}

	@Test
	public void tappingTheChannelNameOpensTheSettings() {
		ProbeFeed feed = openFeed();
		feed.findViewById(R.id.channelFeedToolbar).performClick();
		Intent next = shadowOf(feed).getNextStartedActivity();
		assertNotNull("the toolbar opened nothing", next);
		assertEquals(SETTINGS, next.getComponent().getClassName());
	}

	@Test
	public void theListMenuIsShortAndOpensTheSettings() {
		ProbeHost host = Robolectric.buildActivity(ProbeHost.class)
				.setup().get();
		host.setTheme(R.style.ZerionTheme_NoActionBar);
		ProbeList list = new ProbeList();
		host.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, list).commitNow();
		runBackgroundWork();
		View row = list.requireView().findViewById(R.id.homeRowPreview);
		assertNotNull("no channel row", row);
		((View) row.getParent()).performLongClick();
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
		ListAdapter items = menu.getListView().getAdapter();
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < items.getCount(); i++) {
			labels.add(String.valueOf(items.getItem(i)));
		}
		assertEquals(Arrays.asList("Channel settings", "Share invite link",
				"Send to a contact…"), labels);
		menu.getListView().performItemClick(null, 0, 0);
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals(SETTINGS, shadowOf(host).getNextStartedActivity()
				.getComponent().getClassName());
	}

	private static void runBackgroundWork() {
		for (int round = 0; round < 6; round++) {
			shadowOf(Looper.getMainLooper()).idle();
			if (io.isEmpty()) break;
			List<Runnable> pending = new ArrayList<>(io);
			io.clear();
			for (Runnable r : pending) {
				try {
					r.run();
				} catch (RuntimeException ignored) {
				}
			}
		}
		shadowOf(Looper.getMainLooper()).idle();
	}
}
