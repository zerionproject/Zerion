package com.professor.zerion.android.channel;

import android.content.Intent;
import android.os.Looper;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.SnackbarProbe;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelNoAccessException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelEditorPostErrorTest {

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

	@Before
	public void setUp() throws Exception {
		manager = mock(ChannelManager.class);
		io.clear();
		ShadowToast.reset();
		manager = mock(ChannelManager.class);
		ChannelState s = new ChannelState(new byte[32], new byte[16],
				new byte[32], new byte[0], "News", "", null, HOUR, true, null,
				"", 1L, false, 0L);
		when(manager.getChannel(any())).thenReturn(s);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		when(manager.canPost(any())).thenReturn(true);
		when(manager.getCommentExceptions(any()))
				.thenReturn(Collections.emptySet());
	}

	private String postAndRead() {
		ProbeFeed feed = Robolectric.buildActivity(ProbeFeed.class,
				new Intent(ApplicationProvider.getApplicationContext(),
						ProbeFeed.class)).setup().get();
		runBackgroundWork();
		EditText input = feed.findViewById(R.id.channelComposeInput);
		input.setText("Hello");
		feed.findViewById(R.id.channelComposeSendButton).performClick();
		runBackgroundWork();
		return SnackbarProbe.text(feed);
	}

	@Test
	public void anEditorWhoLostAccessIsToldSo() throws Exception {
		doThrow(new ChannelNoAccessException()).when(manager)
				.publishPostWithSeq(any(), anyString(), anyLong());
		assertEquals("You no longer have access to this channel. Ask the"
				+ " owner for a new invite link.", postAndRead());
	}

	@Test
	public void aRefusedEditorPostSaysWhyItMayHaveBeenRefused()
			throws Exception {
		doThrow(new DbException()).when(manager)
				.publishPostWithSeq(any(), anyString(), anyLong());
		String text = postAndRead();
		assertEquals("Could not post. You may have posted a lot recently,"
				+ " or you may no longer be an editor of this channel.",
				text);
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
