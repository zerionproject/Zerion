package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
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
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelRenewLinkWordingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager;

	public static class ProbeSettings extends ChannelSettingsActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			eventBus = mock(EventBus.class);
			accountManager = mock(AccountManager.class);
			ioExecutor = io::add;
		}
	}

	public static class ProbeSubscribers extends ChannelSubscribersActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			ioExecutor = io::add;
		}
	}

	private final byte[] id = new byte[32];
	private final byte[] dana = new byte[32];

	{
		java.util.Arrays.fill(dana, (byte) 5);
	}

	@Before
	public void setUp() throws Exception {
		manager = mock(ChannelManager.class);
		io.clear();
		ShadowDialog.reset();
		manager = mock(ChannelManager.class);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		when(manager.getOwnerDeletedChannelNames())
				.thenReturn(Collections.emptyList());
		when(manager.getAnnouncedSubscribers(any())).thenReturn(
				Collections.singletonList(new ChannelSubscriber("Dana",
						dana, new byte[0], 0L, false)));
		when(manager.getBannedKeys(any()))
				.thenReturn(Collections.<byte[]>emptyList());
	}

	private void ownPrivateChannel(boolean approval) throws Exception {
		ChannelState s = new ChannelState(id, new byte[16], new byte[32],
				new byte[0], "News", "", null, HOUR, false, new byte[32], "",
				1L, true, 0L, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, approval);
		when(manager.getChannel(any())).thenReturn(s);
		when(manager.getChannels()).thenReturn(Collections.singletonList(s));
	}

	private String renewMessage() {
		ProbeSettings screen = Robolectric.buildActivity(ProbeSettings.class,
				ChannelSettingsActivity.intent(
						ApplicationProvider.getApplicationContext(), id)
						.setClass(ApplicationProvider.getApplicationContext(),
								ProbeSettings.class)).setup().get();
		runBackgroundWork();
		View renew = screen.findViewById(R.id.channelSettingsRenew);
		assertEquals(View.VISIBLE, renew.getVisibility());
		renew.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		Dialog confirm = ShadowDialog.getLatestDialog();
		TextView message = confirm.findViewById(android.R.id.message);
		assertNotNull(message);
		return message.getText().toString();
	}

	private String removalChoice() {
		ProbeSubscribers screen = Robolectric.buildActivity(
				ProbeSubscribers.class,
				new Intent(ApplicationProvider.getApplicationContext(),
						ProbeSubscribers.class)).setup().get();
		runBackgroundWork();
		RecyclerView list = screen.findViewById(R.id.subscribersRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		assertTrue("no subscriber row", list.getChildCount() > 0);
		list.getChildAt(0).performClick();
		runBackgroundWork();
		Dialog sheet = ShadowDialog.getLatestDialog();
		assertNotNull("no member sheet", sheet);
		View remove = sheet.findViewById(R.id.channelMemberRemove);
		assertEquals(View.VISIBLE, remove.getVisibility());
		remove.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		Dialog confirm = ShadowDialog.getLatestDialog();
		TextView message = confirm.findViewById(android.R.id.message);
		assertNotNull(message);
		return message.getText().toString();
	}

	@Test
	public void renewingAnApprovalChannelSaysEveryoneIsApprovedAgain()
			throws Exception {
		ownPrivateChannel(true);
		assertTrue(renewMessage().contains("approved again"));
		assertTrue(removalChoice().contains("approval"));
	}

	@Test
	public void renewingAChannelWithoutApprovalDoesNotMentionIt()
			throws Exception {
		ownPrivateChannel(false);
		assertFalse(renewMessage().contains("approv"));
		assertFalse(removalChoice().contains("approv"));
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
