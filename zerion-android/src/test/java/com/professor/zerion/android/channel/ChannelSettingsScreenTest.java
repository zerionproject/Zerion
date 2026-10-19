package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.ChannelApplication;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.ChannelSyncStatus;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelSettingsScreenTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager = mock(ChannelManager.class);
	static AccountManager accounts = mock(AccountManager.class);

	public static class ProbeSettings extends ChannelSettingsActivity {

		static boolean muted;
		static int muteWrites;

		@Override
		boolean isMuted() {
			return muted;
		}

		@Override
		void setMuted(boolean muted) {
			ProbeSettings.muted = muted;
			muteWrites++;
		}

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			eventBus = mock(EventBus.class);
			accountManager = accounts;
			ioExecutor = io::add;
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final byte[] id = new byte[32];

	@Before
	public void setUp() throws Exception {
		io.clear();
		ShadowDialog.reset();
		manager = mock(ChannelManager.class);
		accounts = mock(AccountManager.class);
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		ProbeSettings.muted = false;
		ProbeSettings.muteWrites = 0;
	}

	private ChannelState channel(boolean owner, boolean publicChannel,
			boolean approval) throws Exception {
		ChannelState s = new ChannelState(id, new byte[16], new byte[32],
				new byte[0], "Neighbourhood updates", "News from the street",
				null, HOUR, publicChannel, publicChannel ? null : new byte[32],
				"", 1L, owner, 0L, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, approval);
		when(manager.getChannel(any())).thenReturn(s);
		return s;
	}

	private ProbeSettings open() {
		ProbeSettings screen = Robolectric.buildActivity(ProbeSettings.class,
				ChannelSettingsActivity.intent(ctx, id)
						.setClass(ctx, ProbeSettings.class)).setup().get();
		runBackgroundWork();
		return screen;
	}

	private static boolean shown(ChannelSettingsActivity screen, int id) {
		View v = screen.findViewById(id);
		assertNotNull(v);
		return v.getVisibility() == View.VISIBLE;
	}

	private static String text(ChannelSettingsActivity screen, int id) {
		return String.valueOf(((TextView) screen.findViewById(id)).getText());
	}

	private void ownerWithMembers() throws Exception {
		channel(true, false, true);
		when(manager.listPendingApplications(any())).thenReturn(Arrays.asList(
				mock(ChannelApplication.class), mock(ChannelApplication.class)));
		byte[] banned = new byte[32];
		banned[0] = 9;
		when(manager.getAnnouncedSubscribers(any())).thenReturn(Arrays.asList(
				new ChannelSubscriber("Robin", new byte[32], new byte[0], 0L,
						false),
				new ChannelSubscriber("Sam", banned, new byte[0], 0L, true)));
		when(manager.getBannedKeys(any()))
				.thenReturn(Collections.singletonList(banned));
		when(manager.listActiveDelegations(any())).thenReturn(Arrays.asList(
				mock(ChannelDelegationCert.class),
				mock(ChannelDelegationCert.class)));
	}

	@Test
	public void theOwnerSeesEverySectionWithTheAccessLine() throws Exception {
		ownerWithMembers();
		ProbeSettings screen = open();
		assertEquals("Neighbourhood updates",
				text(screen, R.id.channelSettingsName));
		assertEquals("Private channel · Approval required · You are the owner",
				text(screen, R.id.channelSettingsAccess));
		assertEquals("N", text(screen, R.id.channelSettingsAvatar));
		for (int section : new int[] {R.id.channelSettingsSectionChannel,
				R.id.channelSettingsSectionAccess,
				R.id.channelSettingsSectionParticipation,
				R.id.channelSettingsSectionAvailability,
				R.id.channelSettingsSectionMembers,
				R.id.channelSettingsSectionAdministration}) {
			assertTrue(shown(screen, section));
		}
		assertTrue(shown(screen, R.id.channelSettingsDelete));
		assertFalse(shown(screen, R.id.channelSettingsLeave));
		assertFalse(shown(screen, R.id.channelSettingsAnnounce));
		assertTrue(shown(screen, R.id.channelSettingsRenew));
		assertEquals("People with your invite link ask first, and you"
				+ " approve each one",
				text(screen, R.id.channelSettingsWhoCanJoinSummary));
		assertEquals("You and 2 editors",
				text(screen, R.id.channelSettingsWhoCanPostValue));
		assertEquals("2", text(screen, R.id.channelSettingsWaitingCount));
		assertEquals("Waiting for approval, 2 people", String.valueOf(
				screen.findViewById(R.id.channelSettingsWaiting)
						.getContentDescription()));
		assertEquals("1", text(screen, R.id.channelSettingsSubscribersValue));
		assertEquals("1 person shared their name. Others can read without"
				+ " sharing a name and are not counted.", String.valueOf(
				screen.findViewById(R.id.channelSettingsSubscribers)
						.getContentDescription()));
		ViewGroup row = screen.findViewById(R.id.channelSettingsSubscribers);
		List<String> rowTexts = new ArrayList<>();
		collect(row, rowTexts);
		assertTrue(rowTexts.toString(), rowTexts.contains(
				"People who shared their name"));
		assertFalse(rowTexts.toString(), rowTexts.contains("Subscribers"));
		assertEquals("2", text(screen, R.id.channelSettingsEditorsValue));
		assertEquals("1", text(screen, R.id.channelSettingsBlockedValue));
		assertEquals("Subscribers get new posts while Zerion runs on this"
				+ " phone", text(screen, R.id.channelSettingsConnectionSummary));
	}

	@Test
	public void aSubscriberSeesOnlyWhatASubscriberCanDo() throws Exception {
		channel(false, false, true);
		long at = System.currentTimeMillis();
		when(manager.getSyncStatus(any()))
				.thenReturn(new ChannelSyncStatus(at, false, false));
		ProbeSettings screen = open();
		assertEquals("Private channel · Approval required",
				text(screen, R.id.channelSettingsAccess));
		assertFalse(shown(screen, R.id.channelSettingsSectionMembers));
		assertFalse(shown(screen, R.id.channelSettingsDelete));
		assertFalse(shown(screen, R.id.channelSettingsRenew));
		assertFalse(shown(screen, R.id.channelSettingsComments));
		assertFalse(shown(screen, R.id.channelSettingsWhoCanJoin));
		assertTrue(shown(screen, R.id.channelSettingsLeave));
		assertTrue(shown(screen, R.id.channelSettingsAnnounce));
		assertTrue(shown(screen, R.id.channelSettingsMute));
		assertTrue(shown(screen, R.id.channelSettingsShare));
		assertEquals(ctx.getString(R.string.channels_sync_last_updated,
				android.text.format.DateUtils.formatDateTime(ctx, at,
						android.text.format.DateUtils.FORMAT_SHOW_TIME)),
				text(screen, R.id.channelSettingsConnectionSummary));
		verify(manager, never()).getBannedKeys(any());
		verify(manager, never()).listPendingApplications(any());
	}

	@Test
	public void anEditorIsToldTheyAreAnEditor() throws Exception {
		channel(false, true, false);
		when(manager.canPost(any())).thenReturn(true);
		ProbeSettings screen = open();
		assertEquals("Public channel · You are an editor",
				text(screen, R.id.channelSettingsAccess));
		assertTrue(shown(screen, R.id.channelSettingsLeave));
		assertFalse(shown(screen, R.id.channelSettingsDelete));
	}

	@Test
	public void theNormalViewUsesNoTechnicalWords() throws Exception {
		ownerWithMembers();
		List<String> texts = new ArrayList<>();
		collect(open().getWindow().getDecorView(), texts);
		channel(false, false, true);
		when(manager.getSyncStatus(any())).thenReturn(ChannelSyncStatus.UNKNOWN);
		collect(open().getWindow().getDecorView(), texts);
		assertTrue(texts.size() > 20);
		for (String t : texts) {
			String lower = t.toLowerCase(Locale.ROOT);
			for (String word : new String[] {"onion", "epoch", "manifest",
					"publisher", "address", " key", "checkpoint", "online",
					"mirror"}) {
				assertFalse(t, lower.contains(word));
			}
		}
	}

	private static void collect(View v, List<String> out) {
		if (v.getVisibility() != View.VISIBLE) return;
		if (v instanceof TextView) {
			CharSequence t = ((TextView) v).getText();
			if (t != null && t.length() > 0) out.add(t.toString());
		}
		if (v.getContentDescription() != null) {
			out.add(v.getContentDescription().toString());
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collect(g.getChildAt(i), out);
			}
		}
	}

	@Test
	public void sectionTitlesAreHeadings() throws Exception {
		ownerWithMembers();
		ProbeSettings screen = open();
		ViewGroup section = screen.findViewById(
				R.id.channelSettingsSectionMembers);
		assertTrue(section.getChildAt(0).isAccessibilityHeading());
		assertEquals("Members", String.valueOf(
				((TextView) section.getChildAt(0)).getText()));
	}

	@Test
	public void theMuteSwitchMutesThisChannel() throws Exception {
		channel(false, true, false);
		ProbeSettings screen = open();
		MaterialSwitch mute = screen.findViewById(R.id.channelSettingsMute);
		assertFalse(mute.isChecked());
		mute.performClick();
		assertTrue(ProbeSettings.muted);
		mute.performClick();
		assertFalse(ProbeSettings.muted);
	}

	@Test
	public void aMutedChannelShowsTheSwitchOn() throws Exception {
		channel(false, true, false);
		ProbeSettings.muted = true;
		MaterialSwitch mute = open().findViewById(R.id.channelSettingsMute);
		assertTrue(mute.isChecked());
		assertTrue("showing the state changed it", ProbeSettings.muted);
	}

	@Test
	public void turningCommentsOffAsksFirst() throws Exception {
		channel(true, true, false);
		ProbeSettings screen = open();
		MaterialSwitch comments = screen.findViewById(
				R.id.channelSettingsComments);
		assertTrue(comments.isChecked());
		comments.performClick();
		idle();
		assertTrue("changed before confirming", comments.isChecked());
		AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(confirm);
		verify(manager, never()).setDiscussionsEnabled(any(), anyBoolean());
		confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		runBackgroundWork();
		verify(manager).setDiscussionsEnabled(id, false);
	}

	@Test
	public void deletingStartsWithTheExplanationNotADeletion()
			throws Exception {
		channel(true, false, false);
		ProbeSettings screen = open();
		screen.findViewById(R.id.channelSettingsDelete).performClick();
		idle();
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue(d instanceof AlertDialog);
		assertNotNull(d.findViewById(R.id.channelDeleteWordInput));
		assertFalse(((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE)
				.isEnabled());
		runBackgroundWork();
		verify(manager, never()).deleteChannelForEveryone(any());
		verify(manager, never()).deleteChannel(any());
		verify(manager, never()).leaveChannel(any());
	}

	@Test
	public void aSubscriberLeavesAfterConfirming() throws Exception {
		channel(false, true, false);
		ProbeSettings screen = open();
		screen.findViewById(R.id.channelSettingsLeave).performClick();
		idle();
		AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
		verify(manager, never()).leaveChannel(any());
		confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		runBackgroundWork();
		verify(manager).leaveChannel(id);
		assertTrue(screen.isFinishing());
	}

	@Test
	public void advancedHoldsTheDetailsAndTheChannelId() throws Exception {
		channel(false, false, false);
		when(manager.getSyncStatus(any())).thenReturn(new ChannelSyncStatus(
				System.currentTimeMillis() - 100L * 24L * HOUR, true, true));
		when(manager.getPostsGivenUp(any())).thenReturn(3L);
		byte[] mine = new byte[64];
		for (int i = 0; i < mine.length; i++) mine[i] = (byte) i;
		when(manager.getMyChannelPublicKey(any())).thenReturn(mine);
		ProbeSettings screen = open();
		screen.findViewById(R.id.channelSettingsConnection).performClick();
		idle();
		AlertDialog advanced = (AlertDialog) ShadowDialog.getLatestDialog();
		String message = String.valueOf(((TextView) advanced.findViewById(
				android.R.id.message)).getText());
		assertTrue(message, message.contains("Opening the channel checks"
				+ " again"));
		assertTrue(message, message.contains("Older posts were removed"));
		assertTrue(message, message.contains("Your safety code in this"
				+ " channel: " + ChannelMember.ownSafetyCode(mine)));
		assertTrue(message, ChannelMember.ownSafetyCode(mine).matches(
				"[0-9]{10}( [0-9]{10}){5}"));
		assertEquals("Copy my channel ID", String.valueOf(advanced.getButton(
				AlertDialog.BUTTON_NEUTRAL).getText()));
	}

	@Test
	public void theOwnersAdvancedHasNoChannelIdToCopy() throws Exception {
		channel(true, true, false);
		when(manager.hasOutdatedSubscribers(any())).thenReturn(true);
		ProbeSettings screen = open();
		screen.findViewById(R.id.channelSettingsConnection).performClick();
		idle();
		AlertDialog advanced = (AlertDialog) ShadowDialog.getLatestDialog();
		String message = String.valueOf(((TextView) advanced.findViewById(
				android.R.id.message)).getText());
		assertTrue(message, message.contains("older version of Zerion"));
		assertEquals(View.GONE, advanced.getButton(AlertDialog.BUTTON_NEUTRAL)
				.getVisibility());
	}

	@Test
	public void theBlockedRowOpensOnlyTheBlockedList() throws Exception {
		ownerWithMembers();
		ProbeSettings screen = open();
		assertTrue(shown(screen, R.id.channelSettingsBlocked));
		screen.findViewById(R.id.channelSettingsBlocked).performClick();
		Intent next = shadowOf(screen).getNextStartedActivity();
		Intent blocked = ChannelSubscribersActivity.blockedIntent(ctx, id);
		assertEquals(blocked.getComponent(), next.getComponent());
		assertEquals(blocked.getExtras().keySet(),
				next.getExtras().keySet());
	}

	@Test
	public void aRecreatedScreenStillGetsTheDeleteResult() throws Exception {
		channel(true, false, false);
		org.mockito.Mockito.doThrow(new org.zerionproject.app.api.channel
				.ChannelDeleteNotSignedException()).when(manager)
				.deleteChannelForEveryone(any());
		org.robolectric.android.controller.ActivityController<ProbeSettings>
				c = Robolectric.buildActivity(ProbeSettings.class,
				ChannelSettingsActivity.intent(ctx, id)
						.setClass(ctx, ProbeSettings.class)).setup();
		runBackgroundWork();
		c.get().findViewById(R.id.channelSettingsDelete).performClick();
		idle();
		AlertDialog word = (AlertDialog) ShadowDialog.getLatestDialog();
		((TextView) word.findViewById(R.id.channelDeleteWordInput))
				.setText("DELETE");
		word.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		Dialog sheet = ShadowDialog.getLatestDialog();
		((TextView) sheet.findViewById(R.id.channelDeleteAuthPassword))
				.setText("my password");
		sheet.findViewById(R.id.channelDeleteAuthConfirm).performClick();
		idle();
		int shown = ShadowDialog.getShownDialogs().size();
		c.recreate();
		runBackgroundWork();
		AlertDialog offer = shownOffer(shown);
		assertNotNull("the result was lost", offer);
		List<Dialog> dialogs = ShadowDialog.getShownDialogs();
		Dialog busy = dialogs.get(shown);
		assertNotNull("not the running check",
				busy.findViewById(R.id.channelDeleteAuthConfirm));
		assertFalse(busy.isShowing());
		assertOnly(shown, busy, offer);
		shown = ShadowDialog.getShownDialogs().size();
		c.recreate();
		idle();
		AlertDialog again = shownOffer(shown);
		assertNotNull("the offer was lost when the screen turned", again);
		assertOnly(shown, again);
		again.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idle();
		shown = ShadowDialog.getShownDialogs().size();
		c.recreate();
		idle();
		assertOnly(shown);
		verify(manager).deleteChannelForEveryone(id);
		verify(manager, never()).deleteChannel(any());
	}

	private static void assertOnly(int from, Dialog... expected) {
		List<Dialog> dialogs = ShadowDialog.getShownDialogs();
		List<Dialog> since = new ArrayList<>(dialogs.subList(from,
				dialogs.size()));
		StringBuilder names = new StringBuilder();
		for (Dialog d : since) {
			TextView title = d.findViewById(
					androidx.appcompat.R.id.alertTitle);
			names.append(title == null ? d.getClass().getSimpleName()
					: String.valueOf(title.getText())).append("; ");
		}
		assertEquals("dialogs after turning the screen: " + names,
				java.util.Arrays.asList(expected), since);
	}

	@Test
	public void turningTheScreenAsksNothingAndChangesNothing()
			throws Exception {
		channel(true, false, false);
		ProbeSettings.muted = true;
		org.robolectric.android.controller.ActivityController<ProbeSettings>
				c = Robolectric.buildActivity(ProbeSettings.class,
				ChannelSettingsActivity.intent(ctx, id)
						.setClass(ctx, ProbeSettings.class)).setup();
		runBackgroundWork();
		for (int turn = 0; turn < 2; turn++) {
			int shown = ShadowDialog.getShownDialogs().size();
			c.recreate();
			runBackgroundWork();
			assertOnly(shown);
		}
		assertEquals(0, ProbeSettings.muteWrites);
		assertTrue(((MaterialSwitch) c.get().findViewById(
				R.id.channelSettingsComments)).isChecked());
		assertTrue(((MaterialSwitch) c.get().findViewById(
				R.id.channelSettingsMute)).isChecked());
		verify(manager, never()).setDiscussionsEnabled(any(), anyBoolean());
	}

	@Test
	public void theSwitchesAreNotRestoredFromSavedState() throws Exception {
		org.w3c.dom.Element root = javax.xml.parsers.DocumentBuilderFactory
				.newInstance().newDocumentBuilder().parse(new java.io.File(
						"src/main/res/layout/activity_channel_settings.xml"))
				.getDocumentElement();
		org.w3c.dom.NodeList all = root.getElementsByTagName("*");
		int switches = 0;
		for (int i = 0; i < all.getLength(); i++) {
			org.w3c.dom.Element e = (org.w3c.dom.Element) all.item(i);
			if (!e.getTagName().endsWith("Switch")) continue;
			switches++;
			assertEquals(e.getAttribute("android:id"), "false",
					e.getAttribute("android:saveEnabled"));
		}
		assertEquals(2, switches);
	}

	@androidx.annotation.Nullable
	private static AlertDialog shownOffer(int from) {
		AlertDialog found = null;
		List<Dialog> dialogs = ShadowDialog.getShownDialogs();
		for (Dialog d : dialogs.subList(from, dialogs.size())) {
			if (!(d instanceof AlertDialog) || !d.isShowing()) continue;
			TextView m = d.findViewById(android.R.id.message);
			if (m != null && String.valueOf(m.getText()).startsWith(
					"Could not delete the channel for everyone. Nothing was"
							+ " removed.")) {
				found = (AlertDialog) d;
			}
		}
		return found;
	}

	@Test
	public void aRecreatedScreenShowsTheCheckRunningAndThenItsAnswer()
			throws Exception {
		channel(true, false, false);
		org.mockito.Mockito.doThrow(new org.zerionproject.core.api.crypto
				.DecryptionException(org.zerionproject.core.api.crypto
				.DecryptionResult.INVALID_PASSWORD)).when(accounts)
				.verifyPassword(any());
		org.robolectric.android.controller.ActivityController<ProbeSettings>
				c = Robolectric.buildActivity(ProbeSettings.class,
				ChannelSettingsActivity.intent(ctx, id)
						.setClass(ctx, ProbeSettings.class)).setup();
		runBackgroundWork();
		c.get().findViewById(R.id.channelSettingsDelete).performClick();
		idle();
		AlertDialog word = (AlertDialog) ShadowDialog.getLatestDialog();
		((TextView) word.findViewById(R.id.channelDeleteWordInput))
				.setText("DELETE");
		word.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		Dialog sheet = ShadowDialog.getLatestDialog();
		((TextView) sheet.findViewById(R.id.channelDeleteAuthPassword))
				.setText("not my password");
		sheet.findViewById(R.id.channelDeleteAuthConfirm).performClick();
		idle();
		int shown = ShadowDialog.getShownDialogs().size();
		c.recreate();
		idle();
		Dialog running = null;
		for (Dialog d : ShadowDialog.getShownDialogs()) {
			if (d != sheet && d.isShowing() && d.findViewById(
					R.id.channelDeleteAuthConfirm) != null) {
				running = d;
			}
		}
		assertNotNull("the recreated screen lost the running check", running);
		assertOnly(shown, running);
		assertFalse(running.findViewById(R.id.channelDeleteAuthConfirm)
				.isEnabled());
		assertEquals(View.VISIBLE, running.findViewById(
				R.id.channelDeleteAuthProgress).getVisibility());
		runBackgroundWork();
		assertTrue(running.isShowing());
		com.google.android.material.textfield.TextInputLayout layout =
				running.findViewById(R.id.channelDeleteAuthPasswordLayout);
		assertEquals("Wrong password. Nothing was deleted.",
				String.valueOf(layout.getError()));
		verify(manager, never()).deleteChannelForEveryone(any());
	}

	@Test
	public void aChannelThatIsGoneClosesTheScreen() throws Exception {
		when(manager.getChannel(any())).thenReturn(null);
		assertTrue(open().isFinishing());
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void runBackgroundWork() {
		for (int round = 0; round < 6; round++) {
			idle();
			if (io.isEmpty()) break;
			List<Runnable> pending = new ArrayList<>(io);
			io.clear();
			for (Runnable r : pending) r.run();
		}
		idle();
	}
}
