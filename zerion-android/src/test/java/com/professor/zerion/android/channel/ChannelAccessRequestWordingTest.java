package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
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
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.app.api.channel.ApplicationStatus;
import org.zerionproject.app.api.channel.ChannelApplication;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.db.DbException;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelAccessRequestWordingTest {

	static {
		TestAndroidKeyStore.register();
	}

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager;

	public static class ProbePending
			extends ChannelPendingApplicationsActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			ioExecutor = io::add;
		}
	}

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

	private static final String NAME_MISSING =
			"Enter the name the owner will see.";

	@Before
	public void setUp() throws Exception {
		manager = mock(ChannelManager.class);
		io.clear();
		ShadowDialog.reset();
		ShadowToast.reset();
		manager = mock(ChannelManager.class);
		ChannelApplication app = new ChannelApplication("Dana",
				new byte[32], new byte[0], new byte[32], 0L,
				ChannelApplication.Status.PENDING, null, null);
		when(manager.listPendingApplications(any()))
				.thenReturn(Collections.singletonList(app));
	}

	private ProbePending open() {
		ProbePending screen = Robolectric.buildActivity(ProbePending.class,
				new Intent(ApplicationProvider.getApplicationContext(),
						ProbePending.class)).setup().get();
		runBackgroundWork();
		RecyclerView list = screen.findViewById(
				R.id.pendingApplicationsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		return screen;
	}

	private void answer(ProbePending screen, int button) {
		View row = screen.findViewById(button);
		assertNotNull("no request row", row);
		row.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		((androidx.appcompat.app.AlertDialog) dialog)
				.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runBackgroundWork();
	}

	@Test
	public void theOwnerIsToldTheAnswerFailedNotThatARequestFailed()
			throws Exception {
		doThrow(new DbException()).when(manager)
				.approveApplication(any(), any());
		doThrow(new DbException()).when(manager)
				.denyApplication(any(), any());
		ProbePending screen = open();
		answer(screen, R.id.applicantApproveButton);
		assertEquals("Could not answer this request. Try again.",
				ShadowToast.getTextOfLatestToast());
		ShadowToast.reset();
		answer(screen, R.id.applicantDenyButton);
		assertEquals("Could not answer this request. Try again.",
				ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void theRequestsScreenSaysAccessRequests() throws Exception {
		when(manager.listPendingApplications(any())).thenReturn(
				Collections.<ChannelApplication>emptyList());
		ProbePending screen = open();
		TextView empty = screen.findViewById(
				R.id.pendingApplicationsEmptyView);
		assertEquals("No requests waiting.", empty.getText().toString());
	}

	@Test
	public void aRequestShowsTheDayItWasMadeNotAKey() throws Exception {
		long hour = System.currentTimeMillis() / 3600000L * 3600000L;
		byte[] key = new byte[32];
		java.util.Arrays.fill(key, (byte) 0xab);
		ChannelApplication dana = new ChannelApplication("Dana", key,
				new byte[0], new byte[32], hour,
				ChannelApplication.Status.PENDING, null, null);
		ChannelApplication nobody = new ChannelApplication("  ", key,
				new byte[0], new byte[32], hour,
				ChannelApplication.Status.PENDING, null, null);
		ChannelApplication otherDana = new ChannelApplication("Dana",
				new byte[32], new byte[0], new byte[32], hour - 3600000L,
				ChannelApplication.Status.PENDING, null, null);
		when(manager.listPendingApplications(any()))
				.thenReturn(java.util.Arrays.asList(dana, nobody, otherDana));
		ProbePending screen = open();
		RecyclerView list = screen.findViewById(
				R.id.pendingApplicationsRecycler);
		String when = com.professor.zerion.android.util.HourTimes.withDay(
				screen, hour, System.currentTimeMillis());
		String toTheMinute = android.text.format.DateUtils.formatDateTime(
				screen, hour, android.text.format.DateUtils.FORMAT_SHOW_DATE
						| android.text.format.DateUtils.FORMAT_SHOW_TIME
						| android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
		RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
		RecyclerView.ViewHolder second =
				list.findViewHolderForAdapterPosition(1);
		RecyclerView.ViewHolder third =
				list.findViewHolderForAdapterPosition(2);
		assertNotNull(first);
		assertNotNull(second);
		assertNotNull(third);
		String subtitle = ((TextView) first.itemView.findViewById(
				R.id.applicantSubtitle)).getText().toString();
		assertEquals("Requested " + when, subtitle);
		assertTrue(subtitle, subtitle.contains("around"));
		assertFalse("the request names a minute it does not keep",
				subtitle.contains(toTheMinute));
		assertEquals(subtitle, -1, subtitle.indexOf("abab"));
		assertEquals("Dana", ((TextView) first.itemView.findViewById(
				R.id.applicantName)).getText().toString());
		assertEquals("Person without a name", ((TextView) second.itemView
				.findViewById(R.id.applicantName)).getText().toString());
		String otherSubtitle = ((TextView) third.itemView.findViewById(
				R.id.applicantSubtitle)).getText().toString();
		assertEquals("two Danas an hour apart read differently", false,
				subtitle.equals(otherSubtitle));
	}

	@Test
	public void theRequestsTitleCountsTheRequests() {
		ProbePending screen = open();
		Toolbar toolbar = screen.findViewById(
				R.id.pendingApplicationsToolbar);
		assertEquals("Access requests (1)",
				String.valueOf(toolbar.getTitle()));
	}

	private static void sendRequest() {
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("no request dialog", dialog);
		assertNotNull("not the request dialog",
				dialog.findViewById(R.id.channelApplyNameInput));
		((androidx.appcompat.app.AlertDialog) dialog)
				.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runBackgroundWork();
	}

	@Test
	public void anEmptyNameInTheChannelAsksForTheNameTheOwnerSees()
			throws Exception {
		byte[] id = new byte[32];
		ChannelState s = new ChannelState(id, new byte[16], new byte[32],
				new byte[0], "News", "", null, 3600000L, false, null, "", 1L,
				false, 0L, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, true);
		when(manager.getChannel(any())).thenReturn(s);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		when(manager.getMyApplicationStatus(any()))
				.thenReturn(ApplicationStatus.NOT_APPLIED);
		when(manager.getCommentExceptions(any()))
				.thenReturn(Collections.emptySet());
		ProbeFeed feed = Robolectric.buildActivity(ProbeFeed.class,
				new Intent(ApplicationProvider.getApplicationContext(),
						ProbeFeed.class)).setup().get();
		runBackgroundWork();
		TextView banner = feed.findViewById(R.id.channelApprovalBanner);
		assertEquals(View.VISIBLE, banner.getVisibility());
		banner.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		sendRequest();
		assertEquals(NAME_MISSING, ShadowToast.getTextOfLatestToast());
		verify(manager, never()).applyToJoin(any(byte[].class), anyString());
	}

	@Test
	public void anEmptyNameWhenJoiningAsksForTheNameTheOwnerSees()
			throws Exception {
		ChannelInviteLink link = new ChannelInviteLink(new byte[32],
				new byte[32], null, false, null,
				"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx",
				true);
		when(manager.parseInviteLink(anyString())).thenReturn(link);
		when(manager.getChannel(any())).thenReturn(null);
		when(manager.getChannels()).thenReturn(
				Collections.<ChannelState>emptyList());
		when(manager.getOwnerDeletedChannelNames())
				.thenReturn(Collections.emptyList());
		ProbeHost host = Robolectric.buildActivity(ProbeHost.class)
				.setup().get();
		host.setTheme(R.style.ZerionTheme_NoActionBar);
		ProbeList list = new ProbeList();
		host.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, list).commitNow();
		runBackgroundWork();
		list.showJoinDialog();
		shadowOf(Looper.getMainLooper()).idle();
		Dialog join = ShadowDialog.getLatestDialog();
		TextInputEditText input = join.findViewById(R.id.channelJoinLinkInput);
		input.setText("zerion://channel/a/b");
		((androidx.appcompat.app.AlertDialog) join)
				.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runBackgroundWork();
		sendRequest();
		assertEquals(NAME_MISSING, ShadowToast.getTextOfLatestToast());
		verify(manager, never()).applyToJoin(any(byte[].class), anyString());
		verify(manager, never()).joinChannel(any());
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
