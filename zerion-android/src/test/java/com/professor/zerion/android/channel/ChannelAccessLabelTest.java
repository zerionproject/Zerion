package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.textfield.TextInputEditText;
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
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelAccessLabelTest {

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
		manager = mock(ChannelManager.class);
		io.clear();
		ShadowDialog.reset();
		manager = mock(ChannelManager.class);
		long now = System.currentTimeMillis() / HOUR * HOUR;
		ChannelPost post = new ChannelPost(id, 0L, new byte[32], now,
				"saved post", Collections.emptyList(), 0L, new byte[0], true);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.singletonList(post));
		when(manager.getRecentPosts(any(), eq(1L)))
				.thenReturn(Collections.emptyList());
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.getCommentExceptions(any()))
				.thenReturn(Collections.emptySet());
		when(manager.getOwnerDeletedChannelNames())
				.thenReturn(Collections.emptyList());
	}

	private ChannelState channel(boolean publicChannel, boolean approval,
			boolean owner) throws Exception {
		ChannelState s = new ChannelState(id, new byte[16], new byte[32],
				new byte[0], "News", "", null, HOUR, publicChannel,
				publicChannel ? null : new byte[32], "", 1L, owner, 0L, null,
				null, Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, approval);
		when(manager.getChannel(any())).thenReturn(s);
		when(manager.getChannels()).thenReturn(Collections.singletonList(s));
		return s;
	}

	private String toolbarSubtitle() {
		ProbeFeed feed = Robolectric.buildActivity(ProbeFeed.class,
				new Intent(ctx, ProbeFeed.class)).setup().get();
		runBackgroundWork();
		Toolbar toolbar = feed.findViewById(R.id.channelFeedToolbar);
		return String.valueOf(toolbar.getSubtitle());
	}

	private ProbeList listScreen() {
		ProbeHost host = Robolectric.buildActivity(ProbeHost.class)
				.setup().get();
		host.setTheme(R.style.ZerionTheme_NoActionBar);
		ProbeList list = new ProbeList();
		host.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, list).commitNow();
		runBackgroundWork();
		return list;
	}

	private String rowSubtitle() {
		ProbeList list = listScreen();
		TextView subtitle = list.requireView()
				.findViewById(R.id.homeRowPreview);
		assertNotNull("no channel row", subtitle);
		return subtitle.getText().toString();
	}

	@Test
	public void aPublicChannelSaysPublic() throws Exception {
		channel(true, false, false);
		assertEquals("Public channel", toolbarSubtitle());
		assertEquals("Public channel", rowSubtitle());
	}

	@Test
	public void aChannelForInvitedPeopleSaysPrivate() throws Exception {
		channel(false, false, false);
		assertEquals("Private channel", toolbarSubtitle());
		assertEquals("Private channel", rowSubtitle());
	}

	@Test
	public void aChannelThatNeedsApprovalSaysSo() throws Exception {
		channel(false, true, false);
		assertEquals("Private channel · Approval required",
				toolbarSubtitle());
		assertEquals("Private channel · Approval required", rowSubtitle());
	}

	@Test
	public void theOwnersRowSaysTheyOwnIt() throws Exception {
		channel(true, false, true);
		assertEquals("Public channel", toolbarSubtitle());
		assertEquals("Public channel · You are the owner", rowSubtitle());
		channel(false, true, true);
		assertEquals("Private channel · Approval required",
				toolbarSubtitle());
		assertEquals("Private channel · Approval required"
				+ " · You are the owner", rowSubtitle());
	}

	private Dialog createDialog() throws Exception {
		when(manager.getChannels()).thenReturn(
				Collections.<ChannelState>emptyList());
		ProbeList list = listScreen();
		list.showCreateDialog();
		shadowOf(Looper.getMainLooper()).idle();
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("no create dialog", dialog);
		return dialog;
	}

	@Test
	public void approvalCanOnlyBeChosenForAPrivateChannel()
			throws Exception {
		Dialog dialog = createDialog();
		RadioButton open = dialog.findViewById(R.id.channelCreatePublicRadio);
		RadioButton invited =
				dialog.findViewById(R.id.channelCreateClosedRadio);
		CheckBox approval = dialog.findViewById(
				R.id.channelCreateRequireApprovalCheckbox);
		assertTrue(open.isChecked());
		assertFalse(approval.isEnabled());
		assertFalse(approval.isChecked());

		invited.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		assertTrue(approval.isEnabled());
		approval.performClick();
		assertTrue(approval.isChecked());

		open.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		assertFalse(approval.isEnabled());
		assertFalse(approval.isChecked());
	}

	@Test
	public void aPublicChannelIsCreatedWithoutApproval() throws Exception {
		ChannelState created = channel(true, false, true);
		when(manager.createChannel(anyString(), anyString(), anyBoolean(),
				anyBoolean())).thenReturn(created);
		when(manager.exportInviteLink(any())).thenReturn("zerion://channel/a");
		Dialog dialog = createDialog();
		TextInputEditText name =
				dialog.findViewById(R.id.channelCreateNameInput);
		name.setText("News");
		CheckBox approval = dialog.findViewById(
				R.id.channelCreateRequireApprovalCheckbox);
		approval.performClick();
		((androidx.appcompat.app.AlertDialog) dialog)
				.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runBackgroundWork();
		verify(manager).createChannel("News", "", true, false);
		verify(manager, never()).createChannel(anyString(), anyString(),
				eq(true), eq(true));
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
