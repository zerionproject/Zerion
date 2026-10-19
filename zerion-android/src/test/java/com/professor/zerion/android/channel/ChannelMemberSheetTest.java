package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.channel.ChannelMember.Action;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelMemberSheetTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager = mock(ChannelManager.class);

	public static class ProbeSubscribers extends ChannelSubscribersActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			ioExecutor = io::add;
		}
	}

	public static class ProbeEditors extends ChannelDelegationsActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			ioExecutor = io::add;
		}
	}

	public static class ProbeComments extends ChannelCommentsActivity {

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
	private final byte[] robin = key(1);
	private final byte[] robinMlDsa = key(2);
	private final byte[] ownerKey = key(7);

	private static byte[] key(int b) {
		byte[] k = new byte[32];
		Arrays.fill(k, (byte) b);
		return k;
	}

	@Before
	public void setUp() throws Exception {
		io.clear();
		ShadowDialog.reset();
		manager = mock(ChannelManager.class);
		when(manager.getMyChannelPublicKey(any())).thenReturn(ownerKey);
		when(manager.getBannedKeys(any()))
				.thenReturn(Collections.<byte[]>emptyList());
		when(manager.listActiveDelegations(any()))
				.thenReturn(Collections.<ChannelDelegationCert>emptyList());
		when(manager.getAnnouncedSubscribers(any())).thenReturn(
				Collections.singletonList(new ChannelSubscriber("Robin", robin,
						robinMlDsa, 0L, false)));
		when(manager.getComments(any(), anyLong())).thenReturn(
				Collections.singletonList(new ChannelComment(1L, 5L, "hi",
						"Robin", robin, robinMlDsa, HOUR, new byte[0])));
		when(manager.getQueuedItems(any())).thenReturn(Collections.emptyList());
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.areCommentsEnabled(any(), anyLong())).thenReturn(true);
		when(manager.exportInviteLink(any())).thenReturn("link");
	}

	private void channel(boolean weOwn, boolean publicChannel,
			boolean approval) throws Exception {
		ChannelState s = new ChannelState(id, new byte[16], ownerKey,
				new byte[0], "News", "", null, HOUR, publicChannel,
				publicChannel ? null : new byte[32], "", 1L, weOwn, 0L, null,
				null, Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, approval);
		when(manager.getChannel(any())).thenReturn(s);
	}

	private static ChannelMember member(boolean viewerIsOwner,
			boolean publicChannel, boolean announced, boolean editor,
			boolean trusted, boolean blocked) {
		return new ChannelMember(key(1), key(2),
				"Robin", false, false, announced, editor, 3L, trusted, blocked,
				viewerIsOwner, publicChannel, false, false);
	}

	@Test
	public void theOwnerHasEveryModerationActionThatExists() {
		assertEquals(Arrays.asList(Action.TRUST, Action.MAKE_EDITOR,
				Action.REMOVE, Action.BLOCK, Action.SAFETY_CODE),
				member(true, false, true, false, false, false).actions());
		assertEquals(Arrays.asList(Action.UNTRUST, Action.REMOVE_EDITOR,
				Action.BLOCK, Action.SAFETY_CODE),
				member(true, true, true, true, true, false).actions());
	}

	@Test
	public void aBlockedMemberCanOnlyBeUnblocked() {
		assertEquals(Arrays.asList(Action.UNBLOCK, Action.SAFETY_CODE),
				member(true, false, true, false, false, true).actions());
	}

	@Test
	public void aCommenterWhoSharedNoNameCannotBeTrustedOrMadeEditor() {
		assertEquals(Arrays.asList(Action.REMOVE, Action.BLOCK,
				Action.SAFETY_CODE),
				member(true, false, false, false, false, false).actions());
	}

	@Test
	public void othersOnlySeeTheSafetyCode() {
		assertEquals(Collections.singletonList(Action.SAFETY_CODE),
				member(false, false, true, false, false, false).actions());
		ChannelMember owner = new ChannelMember(key(1), key(2), "Owner",
				true, false, true, false, 0L, false, false, true, false,
				false, false);
		assertEquals(Collections.singletonList(Action.SAFETY_CODE),
				owner.actions());
		ChannelMember me = new ChannelMember(key(1), key(2), "Me", false,
				true, true, false, 0L, false, false, true, false, false,
				false);
		assertEquals(Collections.singletonList(Action.SAFETY_CODE),
				me.actions());
	}

	@Test
	public void theSafetyCodeIsTheAppsIdentityFingerprint() {
		String code = ChannelMember.safetyCode(key(1), key(2));
		assertTrue(code, code.matches("[0-9]{10}( [0-9]{10}){5}"));
		assertEquals(com.professor.zerion.android.contact.identity
				.IdentityFingerprint.forIdentity(key(1), key(2)), code);
		assertTrue(!code.equals(ChannelMember.safetyCode(key(1), key(3))));
		assertEquals("", ChannelMember.safetyCode(key(1), new byte[0]));
	}

	@Test
	public void noCodeIsOfferedWithoutBothHalves() {
		ChannelMember halfKnown = new ChannelMember(key(1), new byte[0],
				"Robin", false, false, false, false, 0L, false, true, true,
				false, false, false);
		assertEquals(Collections.singletonList(Action.UNBLOCK),
				halfKnown.actions());
	}

	private ProbeSubscribers subscribers(Intent intent) {
		ProbeSubscribers screen = Robolectric.buildActivity(
				ProbeSubscribers.class, intent).setup().get();
		runBackgroundWork();
		return screen;
	}

	private static View firstRow(RecyclerView list) {
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		assertTrue("no row", list.getChildCount() > 0);
		return list.getChildAt(0);
	}

	private BottomSheetDialog sheet() {
		runBackgroundWork();
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue("no member sheet: " + d, d instanceof BottomSheetDialog);
		assertTrue(d.isShowing());
		return (BottomSheetDialog) d;
	}

	private static boolean shown(Dialog sheet, int id) {
		View v = sheet.findViewById(id);
		assertNotNull(v);
		return v.getVisibility() == View.VISIBLE;
	}

	private AlertDialog tap(Dialog sheet, int id) {
		sheet.findViewById(id).performClick();
		idle();
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue(d instanceof AlertDialog);
		return (AlertDialog) d;
	}

	private void confirm(AlertDialog d) {
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		runBackgroundWork();
	}

	@Test
	public void tappingASubscriberOpensTheMemberSheet() throws Exception {
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		BottomSheetDialog sheet = sheet();
		assertEquals("Robin", String.valueOf(((TextView) sheet.findViewById(
				R.id.channelMemberName)).getText()));
		assertEquals("Subscriber", String.valueOf(((TextView) sheet
				.findViewById(R.id.channelMemberRole)).getText()));
		assertTrue(shown(sheet, R.id.channelMemberTrust));
		assertTrue(shown(sheet, R.id.channelMemberEditor));
		assertTrue(shown(sheet, R.id.channelMemberRemove));
		assertTrue(shown(sheet, R.id.channelMemberBlock));
		assertFalse(shown(sheet, R.id.channelMemberUnblock));
		assertTrue(shown(sheet, R.id.channelMemberSafetyCode));
	}

	@Test
	public void holdingASubscriberNoLongerBansFromAList() throws Exception {
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler))
				.performLongClick();
		sheet();
		verify(manager, never()).banSubscriber(any(), any());
	}

	@Test
	public void blockingAsksFirstAndDoesNotRenewTheLink() throws Exception {
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		AlertDialog ask = tap(sheet(), R.id.channelMemberBlock);
		String message = String.valueOf(((TextView) ask.findViewById(
				android.R.id.message)).getText());
		assertTrue(message, message.startsWith("Block Robin?"));
		assertTrue(message, message.contains("does not stop anyone from"
				+ " reading"));
		verify(manager, never()).banSubscriber(any(), any());
		confirm(ask);
		verify(manager).banSubscriber(id, robin);
		verify(manager, never()).rotateJoinCapability(any());
	}

	@Test
	public void removingRenewsTheLinkForEveryone() throws Exception {
		channel(true, false, true);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		AlertDialog ask = tap(sheet(), R.id.channelMemberRemove);
		String message = String.valueOf(((TextView) ask.findViewById(
				android.R.id.message)).getText());
		assertTrue(message, message.contains("everyone, Robin included"));
		assertTrue(message, message.contains("new approval"));
		assertTrue(message, message.contains(OLD_POSTS));
		confirm(ask);
		verify(manager).banSubscriber(id, robin);
		verify(manager).rotateJoinCapability(id);
	}

	@Test
	public void removingFromAChannelWithoutApprovalDoesNotMentionIt()
			throws Exception {
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		AlertDialog ask = tap(sheet(), R.id.channelMemberRemove);
		String message = String.valueOf(((TextView) ask.findViewById(
				android.R.id.message)).getText());
		assertFalse(message, message.contains("approv"));
		assertTrue(message, message.contains(OLD_POSTS));
	}

	private static final String OLD_POSTS = "Posts from before the change"
			+ " cannot be read with the new link.";

	@Test
	public void removalWarnsAboutOldPostsInTheRenewWords() {
		for (int renew : new int[] {
				R.string.channels_invite_renew_confirm_message,
				R.string.channels_invite_renew_confirm_message_approval,
				R.string.channels_member_remove_confirm,
				R.string.channels_member_remove_confirm_approval}) {
			String text = ctx.getString(renew, "Robin");
			assertTrue(text, text.contains(OLD_POSTS));
		}
	}

	@Test
	public void aPublicChannelOffersNoRemoval() throws Exception {
		channel(true, true, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		BottomSheetDialog sheet = sheet();
		assertFalse(shown(sheet, R.id.channelMemberRemove));
		assertTrue(shown(sheet, R.id.channelMemberBlock));
	}

	@Test
	public void makingAnEditorUsesTheirSharedIdentity() throws Exception {
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		sheet().findViewById(R.id.channelMemberEditor).performClick();
		idle();
		runBackgroundWork();
		verify(manager).delegatePublisher(id, robin, robinMlDsa, 0L);
	}

	@Test
	public void trustingNeedsNoConfirmation() throws Exception {
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		BottomSheetDialog sheet = sheet();
		assertEquals("Trust their comments", String.valueOf(((TextView) sheet
				.findViewById(R.id.channelMemberTrustText)).getText()));
		sheet.findViewById(R.id.channelMemberTrust).performClick();
		idle();
		runBackgroundWork();
		verify(manager).setSubscriberTrusted(id, robin, true);
	}

	@Test
	public void theBlockedListShowsOnlyBlockedPeopleAndUnblocks()
			throws Exception {
		channel(true, false, false);
		byte[] sam = key(4);
		when(manager.getBannedKeys(any()))
				.thenReturn(Collections.singletonList(sam));
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.blockedIntent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		RecyclerView list = screen.findViewById(R.id.subscribersRecycler);
		firstRow(list);
		assertEquals(1, list.getAdapter().getItemCount());
		assertEquals("Blocked", String.valueOf(screen.getTitle()));
		firstRow(list).performClick();
		BottomSheetDialog sheet = sheet();
		assertTrue(shown(sheet, R.id.channelMemberUnblock));
		assertFalse(shown(sheet, R.id.channelMemberBlock));
		assertFalse(shown(sheet, R.id.channelMemberTrust));
		confirm(tap(sheet, R.id.channelMemberUnblock));
		verify(manager).unbanSubscriber(id, sam);
	}

	@Test
	public void aBlockedSubscriberWhoSharedANameKeepsIt() throws Exception {
		channel(true, false, false);
		when(manager.getBannedKeys(any()))
				.thenReturn(Collections.singletonList(robin));
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.blockedIntent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		RecyclerView list = screen.findViewById(R.id.subscribersRecycler);
		View row = firstRow(list);
		assertEquals(1, list.getAdapter().getItemCount());
		assertEquals("Robin", String.valueOf(((TextView) row.findViewById(
				R.id.subscriberName)).getText()));
		assertEquals(View.VISIBLE, row.findViewById(
				R.id.subscriberBannedBadge).getVisibility());
	}

	@Test
	public void theListShowsExactlyThePeopleTheSettingsCount()
			throws Exception {
		channel(true, false, false);
		byte[] sam = key(4);
		byte[] kim = key(5);
		byte[] nameless = key(6);
		when(manager.getAnnouncedSubscribers(any())).thenReturn(Arrays.asList(
				new ChannelSubscriber("Robin", robin, robinMlDsa, 0L, false),
				new ChannelSubscriber("Sam", sam, new byte[0], 0L, true),
				new ChannelSubscriber("Kim", kim, new byte[0], 0L, false)));
		when(manager.getBannedKeys(any()))
				.thenReturn(Arrays.asList(kim, nameless));
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		RecyclerView list = screen.findViewById(R.id.subscribersRecycler);
		View row = firstRow(list);
		assertEquals(1, list.getAdapter().getItemCount());
		assertEquals("Robin", String.valueOf(((TextView) row.findViewById(
				R.id.subscriberName)).getText()));
		assertEquals("People who shared their name (1)",
				String.valueOf(screen.getTitle()));
		ChannelSettingsActivity.Snapshot counted =
				ChannelSettingsActivity.Snapshot.read(manager, id);
		assertNotNull(counted);
		assertEquals(1, counted.subscribers);
		ProbeSubscribers blocked = subscribers(ChannelSubscribersActivity
				.blockedIntent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		RecyclerView blockedList =
				blocked.findViewById(R.id.subscribersRecycler);
		firstRow(blockedList);
		assertEquals(3, blockedList.getAdapter().getItemCount());
		assertEquals(3, counted.blocked);
	}

	@Test
	public void bothSidesSeeOneCodeMadeFromTheWholeIdentity()
			throws Exception {
		String expected = com.professor.zerion.android.contact.identity
				.IdentityFingerprint.forIdentity(robin, robinMlDsa);
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		AlertDialog code = tap(sheet(), R.id.channelMemberSafetyCode);
		String message = String.valueOf(((TextView) code.findViewById(
				android.R.id.message)).getText());
		assertTrue(message, message.startsWith(expected + "\n"));
		channel(false, false, false);
		byte[] own = new byte[robin.length + robinMlDsa.length];
		System.arraycopy(robin, 0, own, 0, robin.length);
		System.arraycopy(robinMlDsa, 0, own, robin.length, robinMlDsa.length);
		when(manager.getMyChannelPublicKey(any())).thenReturn(own);
		ChannelSettingsActivity.Snapshot mine =
				ChannelSettingsActivity.Snapshot.read(manager, id);
		assertNotNull(mine);
		assertEquals(expected, mine.myCode);
		byte[] otherMlDsa = key(3);
		System.arraycopy(otherMlDsa, 0, own, robin.length, otherMlDsa.length);
		assertTrue("the second half is ignored", !expected.equals(
				ChannelSettingsActivity.Snapshot.read(manager, id).myCode));
	}

	@Test
	public void theSafetyCodeIsShownUnderAdvanced() throws Exception {
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		firstRow(screen.findViewById(R.id.subscribersRecycler)).performClick();
		AlertDialog code = tap(sheet(), R.id.channelMemberSafetyCode);
		String message = String.valueOf(((TextView) code.findViewById(
				android.R.id.message)).getText());
		assertTrue(message, message.contains(ChannelMember.safetyCode(robin,
				robinMlDsa)));
		assertTrue(message, message.contains("Robin"));
	}

	@Test
	public void theSubscriberListShowsNoCodes() throws Exception {
		channel(true, false, false);
		ProbeSubscribers screen = subscribers(ChannelSubscribersActivity
				.intent(ctx, id).setClass(ctx, ProbeSubscribers.class));
		View row = firstRow(screen.findViewById(R.id.subscribersRecycler));
		TextView subtitle = row.findViewById(R.id.subscriberSubtitle);
		assertTrue(subtitle.getVisibility() != View.VISIBLE
				|| !subtitle.getText().toString().contains("0101"));
	}

	@Test
	public void tappingACommentAuthorOpensTheMemberSheetForTheOwner()
			throws Exception {
		channel(true, false, false);
		ProbeComments screen = Robolectric.buildActivity(ProbeComments.class,
				ChannelCommentsActivity.intent(ctx, id, 1L)
						.setClass(ctx, ProbeComments.class)).setup().get();
		runBackgroundWork();
		View row = firstRow(screen.findViewById(R.id.commentsRecycler));
		assertTrue(row.findViewById(R.id.commentAuthor).performClick());
		BottomSheetDialog sheet = sheet();
		assertEquals("Robin", String.valueOf(((TextView) sheet.findViewById(
				R.id.channelMemberName)).getText()));
		verify(manager, never()).banSubscriber(any(), any());
	}

	@Test
	public void anEditorRowOpensTheSheetToRemoveTheEditor() throws Exception {
		channel(true, false, false);
		ChannelDelegationCert cert = mock(ChannelDelegationCert.class);
		when(cert.getDelegateeEd25519PubKey()).thenReturn(robin);
		when(cert.getDelegateeMlDsaPubKey()).thenReturn(robinMlDsa);
		when(cert.getDelegationSeq()).thenReturn(4L);
		when(cert.isUnbounded()).thenReturn(true);
		when(manager.listActiveDelegations(any()))
				.thenReturn(Collections.singletonList(cert));
		ProbeEditors screen = Robolectric.buildActivity(ProbeEditors.class,
				ChannelDelegationsActivity.intent(ctx, id)
						.setClass(ctx, ProbeEditors.class)).setup().get();
		runBackgroundWork();
		View row = firstRow(screen.findViewById(R.id.delegationsRecycler));
		assertEquals("Robin", String.valueOf(((TextView) row.findViewById(
				R.id.delegationKeyView)).getText()));
		row.performClick();
		BottomSheetDialog sheet = sheet();
		assertEquals("Editor", String.valueOf(((TextView) sheet.findViewById(
				R.id.channelMemberRole)).getText()));
		assertEquals("Remove editor", String.valueOf(((TextView) sheet
				.findViewById(R.id.channelMemberEditorText)).getText()));
		AlertDialog ask = tap(sheet, R.id.channelMemberEditor);
		verify(manager, never()).revokeDelegation(any(), anyLong());
		confirm(ask);
		verify(manager).revokeDelegation(id, 4L);
	}

	@Test
	public void editorRowsUsePlainWords() throws Exception {
		channel(true, false, false);
		ChannelDelegationCert open = mock(ChannelDelegationCert.class);
		when(open.getDelegateeEd25519PubKey()).thenReturn(key(8));
		when(open.getDelegateeMlDsaPubKey()).thenReturn(key(9));
		when(open.getDelegationSeq()).thenReturn(4L);
		when(open.isUnbounded()).thenReturn(true);
		ChannelDelegationCert until = mock(ChannelDelegationCert.class);
		when(until.getDelegateeEd25519PubKey()).thenReturn(robin);
		when(until.getDelegateeMlDsaPubKey()).thenReturn(robinMlDsa);
		when(until.getDelegationSeq()).thenReturn(5L);
		when(until.isUnbounded()).thenReturn(false);
		when(until.getValidUntilHourMs()).thenReturn(1_800_000_000_000L);
		when(manager.listActiveDelegations(any()))
				.thenReturn(Arrays.asList(open, until));
		ProbeEditors screen = Robolectric.buildActivity(ProbeEditors.class,
				ChannelDelegationsActivity.intent(ctx, id)
						.setClass(ctx, ProbeEditors.class)).setup().get();
		runBackgroundWork();
		RecyclerView list = screen.findViewById(R.id.delegationsRecycler);
		firstRow(list);
		View nameless = list.getChildAt(0);
		assertEquals("Editor without a name", String.valueOf(((TextView)
				nameless.findViewById(R.id.delegationKeyView)).getText()));
		assertEquals("No expiry", String.valueOf(((TextView) nameless
				.findViewById(R.id.delegationValidityView)).getText()));
		View named = list.getChildAt(1);
		assertEquals("Robin", String.valueOf(((TextView) named.findViewById(
				R.id.delegationKeyView)).getText()));
		String validity = String.valueOf(((TextView) named.findViewById(
				R.id.delegationValidityView)).getText());
		assertEquals("Valid until " + android.text.format.DateUtils
				.formatDateTime(ctx, 1_800_000_000_000L,
						android.text.format.DateUtils.FORMAT_SHOW_DATE
								| android.text.format.DateUtils
								.FORMAT_SHOW_YEAR), validity);
		for (View row : new View[] {nameless, named}) {
			List<String> texts = new ArrayList<>();
			collectTexts(row, texts);
			for (String t : texts) {
				assertFalse(t, t.contains("#"));
				assertFalse(t, t.matches(".*[0-9a-f]{8}.*"));
			}
		}
	}

	private static void collectTexts(View v, List<String> out) {
		if (v instanceof TextView) {
			out.add(String.valueOf(((TextView) v).getText()));
		}
		if (v instanceof android.view.ViewGroup) {
			android.view.ViewGroup g = (android.view.ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collectTexts(g.getChildAt(i), out);
			}
		}
	}

	@Test
	public void theEditorPickerShowsNamesOnlyAndOpensTheSheet()
			throws Exception {
		channel(true, false, false);
		long first = 1_790_000_000_000L;
		long later = first + 3L * 24L * HOUR;
		when(manager.getAnnouncedSubscribers(any())).thenReturn(Arrays.asList(
				new ChannelSubscriber("Robin", robin, robinMlDsa, first, false),
				new ChannelSubscriber("Robin", key(4), key(5), later, false),
				new ChannelSubscriber("Sam", key(6), key(7), first, false),
				new ChannelSubscriber("", key(8), key(9), 0L, false),
				new ChannelSubscriber("Kim", key(10), key(11), first, true)));
		ProbeEditors screen = Robolectric.buildActivity(ProbeEditors.class,
				ChannelDelegationsActivity.intent(ctx, id)
						.setClass(ctx, ProbeEditors.class)).setup().get();
		runBackgroundWork();
		screen.findViewById(R.id.delegationsAddRow).performClick();
		runBackgroundWork();
		AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
		android.widget.ListAdapter items = picker.getListView().getAdapter();
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < items.getCount(); i++) {
			labels.add(String.valueOf(items.getItem(i)));
		}
		int flags = android.text.format.DateUtils.FORMAT_SHOW_DATE
				| android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
		assertEquals(Arrays.asList(
				"Robin \u00b7 shared their name " + android.text.format
						.DateUtils.formatDateTime(ctx, first, flags),
				"Robin \u00b7 shared their name " + android.text.format
						.DateUtils.formatDateTime(ctx, later, flags),
				"Sam", "Person without a name",
				ctx.getString(R.string.channels_delegations_paste_id)),
				labels);
		for (String label : labels) {
			assertFalse(label, label.matches(".*[0-9a-f]{8}.*"));
		}
		picker.getListView().performItemClick(null, 2, 2);
		idle();
		BottomSheetDialog sheet = sheet();
		assertEquals("Sam", String.valueOf(((TextView) sheet.findViewById(
				R.id.channelMemberName)).getText()));
		assertEquals("Make editor", String.valueOf(((TextView) sheet
				.findViewById(R.id.channelMemberEditorText)).getText()));
		verify(manager, never()).delegatePublisher(any(), any(), any(),
				anyLong());
	}

	@Test
	public void aSubscriberSeesNoModerationInTheSheet() throws Exception {
		channel(false, false, false);
		when(manager.getMyChannelPublicKey(any())).thenReturn(key(9));
		ProbeComments screen = Robolectric.buildActivity(ProbeComments.class,
				ChannelCommentsActivity.intent(ctx, id, 1L)
						.setClass(ctx, ProbeComments.class)).setup().get();
		runBackgroundWork();
		ChannelMemberSheet.open(screen, manager, io::add, id, robin, "Robin",
				robinMlDsa, () -> {
				});
		BottomSheetDialog sheet = sheet();
		for (int row : new int[] {R.id.channelMemberTrust,
				R.id.channelMemberEditor, R.id.channelMemberRemove,
				R.id.channelMemberBlock, R.id.channelMemberUnblock}) {
			assertFalse(shown(sheet, row));
		}
		assertTrue(shown(sheet, R.id.channelMemberSafetyCode));
		verify(manager, never()).getBannedKeys(any());
		verify(manager, never()).setSubscriberTrusted(any(), any(),
				anyBoolean());
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
