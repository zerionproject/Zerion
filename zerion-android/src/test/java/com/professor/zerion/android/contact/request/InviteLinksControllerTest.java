package com.professor.zerion.android.contact.request;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.ContactLinkLimitException;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.FloodNotice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.professor.zerion.android.contact.request.RequestFixtures.idle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class InviteLinksControllerTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final RequestFixtures.Queue db = new RequestFixtures.Queue();
	private final ContactRequestManager requests =
			mock(ContactRequestManager.class);
	private final List<ContactLinkInfo> links = new ArrayList<>();
	private final List<FloodNotice> notices = new ArrayList<>();
	private RequestFixtures.Host host;
	private View root;

	@Before
	public void setUp() throws Exception {
		ShadowDialog.reset();
		ShadowToast.reset();
		when(requests.getLinks()).thenReturn(links);
		when(requests.getFloodNotices()).thenReturn(notices);
		when(requests.getBlockedSenders(any()))
				.thenReturn(Collections.emptyList());
		host = Robolectric.buildActivity(RequestFixtures.Host.class)
				.setup().get();
		host.setContentView(R.layout.activity_invite_links);
		root = host.findViewById(R.id.inviteLinksRoot);
	}

	private static byte[] linkId(int n) {
		byte[] id = new byte[32];
		id[0] = (byte) n;
		return id;
	}

	private static ContactLinkInfo permanent(int n, String label,
			int undecided, int blocked, boolean open) {
		return new ContactLinkInfo(linkId(n), ContactLink.KIND_PERMANENT,
				ContactLink.STATE_ACTIVE, label, "zerion://link" + n, 0, 0,
				open, undecided, blocked);
	}

	private void open() {
		InviteLinksController c =
				new InviteLinksController(host, root, requests, db);
		c.reload();
		db.runAll();
	}

	private ViewGroup rows() {
		return root.findViewById(R.id.inviteLinksContainer);
	}

	@Test
	public void eachLinkShowsLabelCountsAndInboxState() {
		links.add(new ContactLinkInfo(linkId(0), ContactLink.KIND_ZERO_ID,
				ContactLink.STATE_ACTIVE, null, "zerion://zero", 0, 0, true,
				0, 0));
		links.add(permanent(1, "Support", 2, 1, true));
		links.add(permanent(2, "Press", 1, 0, false));
		open();
		assertEquals(2, rows().getChildCount());
		String first = RequestFixtures.allText(rows().getChildAt(0));
		assertTrue(first, first.contains("Support"));
		assertTrue(first, first.contains("2 requests waiting, 1 blocked"));
		assertTrue(first, first.contains("Receiving requests"));
		String second = RequestFixtures.allText(rows().getChildAt(1));
		assertTrue(second, second.contains("1 request waiting"));
		assertFalse(second, second.contains("0 blocked"));
		assertTrue(second, second.contains("Not receiving right now"));
	}

	@Test
	public void countsOfZeroAreHidden() {
		links.add(permanent(1, "Support", 0, 0, true));
		links.add(permanent(2, "Press", 0, 2, true));
		open();
		assertEquals(View.GONE, rows().getChildAt(0)
				.findViewById(R.id.inviteLinkCounts).getVisibility());
		TextView counts = rows().getChildAt(1)
				.findViewById(R.id.inviteLinkCounts);
		assertEquals(View.VISIBLE, counts.getVisibility());
		assertEquals("2 blocked", counts.getText().toString());
		assertEquals("", InviteLinksController.counts(host.getResources(), 0,
				0));
		assertEquals("1 request waiting", InviteLinksController.counts(
				host.getResources(), 1, 0));
	}

	@Test
	public void theZeroIdShowsUntilWhenItReceivesAndCanBeRenewed()
			throws Exception {
		long shared = System.currentTimeMillis() - 86_400_000L;
		links.add(new ContactLinkInfo(linkId(0), ContactLink.KIND_ZERO_ID,
				ContactLink.STATE_ACTIVE, null, "zerion://zero", 0, shared,
				true, 0, 0));
		open();
		TextView window = root.findViewById(R.id.zeroIdWindow);
		String until = android.text.format.DateUtils.formatDateTime(host,
				shared + 30L * 86_400_000L,
				android.text.format.DateUtils.FORMAT_SHOW_DATE
						| android.text.format.DateUtils.FORMAT_SHOW_YEAR);
		assertEquals("Receiving requests until " + until,
				window.getText().toString());
		assertEquals(View.GONE,
				root.findViewById(R.id.zeroIdKeepReceiving).getVisibility());
		assertEquals(View.VISIBLE, root.findViewById(
				R.id.zeroIdKeepReceivingQuiet).getVisibility());
		root.findViewById(R.id.zeroIdKeepReceivingQuiet).performClick();
		db.runAll();
		verify(requests).zeroIdShared();
	}

	@Test
	public void anExpiredZeroIdSaysItIsNotReceiving() throws Exception {
		long shared = System.currentTimeMillis() - 31L * 86_400_000L;
		links.add(new ContactLinkInfo(linkId(0), ContactLink.KIND_ZERO_ID,
				ContactLink.STATE_ACTIVE, null, "zerion://zero", 0, shared,
				false, 0, 0));
		open();
		assertEquals("Not receiving requests. Share it or tap Keep "
				+ "receiving.", ((TextView) root.findViewById(
				R.id.zeroIdWindow)).getText().toString());
		assertEquals(View.VISIBLE,
				root.findViewById(R.id.zeroIdKeepReceiving).getVisibility());
		assertEquals(View.GONE, root.findViewById(
				R.id.zeroIdKeepReceivingQuiet).getVisibility());
		root.findViewById(R.id.zeroIdKeepReceiving).performClick();
		db.runAll();
		verify(requests).zeroIdShared();
	}

	@Test
	public void keepReceivingIsTheMainButtonWhenNearlyExpired() {
		long shared = System.currentTimeMillis() - 28L * 86_400_000L;
		links.add(new ContactLinkInfo(linkId(0), ContactLink.KIND_ZERO_ID,
				ContactLink.STATE_ACTIVE, null, "zerion://zero", 0, shared,
				true, 0, 0));
		open();
		assertEquals(View.VISIBLE,
				root.findViewById(R.id.zeroIdKeepReceiving).getVisibility());
		assertEquals(View.GONE, root.findViewById(
				R.id.zeroIdKeepReceivingQuiet).getVisibility());
	}

	@Test
	public void nearlyExpiredMeansUnderThreeDaysLeft() {
		long day = 86_400_000L;
		long shared = 1_000_000_000_000L;
		long until = shared + 30L * day;
		assertEquals(3L * day, InviteLinksController.NEARLY_EXPIRED_MS);
		assertTrue(InviteLinksController.keepReceivingIsMain(0, shared));
		assertFalse(InviteLinksController.keepReceivingIsMain(shared,
				shared + day));
		assertFalse(InviteLinksController.keepReceivingIsMain(shared,
				until - 3L * day));
		assertTrue(InviteLinksController.keepReceivingIsMain(shared,
				until - 3L * day + 1));
		assertTrue(InviteLinksController.keepReceivingIsMain(shared, until));
		assertTrue(InviteLinksController.keepReceivingIsMain(shared,
				until + day));
	}

	@Test
	public void linkRowsHaveNoValidityLineAndTheIntroSaysItOnce() {
		links.add(permanent(1, "Support", 0, 0, true));
		open();
		String row = RequestFixtures.allText(rows().getChildAt(0));
		assertFalse(row, row.contains("Valid until"));
		String all = RequestFixtures.allText(root);
		assertTrue(all, all.contains("Invite links stay the same until you "
				+ "remove them. Good for support."));
	}

	@Test
	public void sharingALinkCountsAsShared() throws Exception {
		links.add(permanent(1, "Support", 0, 0, true));
		open();
		rows().getChildAt(0).findViewById(R.id.inviteLinkCopy).performClick();
		db.runAll();
		verify(requests).permanentLinkShared(linkId(1));
	}

	@Test
	public void aSixthLinkIsRefusedWithAPlainMessage() throws Exception {
		for (int i = 1; i <= 5; i++) links.add(permanent(i, "L" + i, 0, 0, true));
		open();
		root.findViewById(R.id.inviteLinksAdd).performClick();
		idle();
		assertEquals("You can have up to 5 links. Remove one first.",
				ShadowToast.getTextOfLatestToast());
		verify(requests, never()).createPermanentLink(anyString());
	}

	@Test
	public void addingAndRemovingALinkTouchTheBannerOnlyOnTheMainThread()
			throws Exception {
		links.add(permanent(1, "Support", 0, 0, true));
		RequestFixtures.WatchedBanner banner =
				RequestFixtures.watchBanner(root);
		InviteLinksController c = new InviteLinksController(host, root,
				requests, new RequestFixtures.Background());
		c.reload();
		idle();
		root.findViewById(R.id.inviteLinksAdd).performClick();
		idle();
		AlertDialog d = RequestFixtures.latestAlert();
		EditText label = d.findViewById(R.id.inviteLabelInput);
		assertNotNull(label);
		label.setText("Work");
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		verify(requests).createPermanentLink("Work");
		rows().getChildAt(0).findViewById(R.id.inviteLinkWithdraw)
				.performClick();
		idle();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		idle();
		verify(requests).withdrawPermanentLink(linkId(1));
		verify(requests, atLeast(3)).isPausedAfterRestore();
		assertEquals(Collections.emptyList(), banner.wrongThread);
	}

	@Test
	public void theLimitFromTheCoreIsShownTheSameWay() throws Exception {
		when(requests.createPermanentLink("Work"))
				.thenThrow(new ContactLinkLimitException());
		open();
		root.findViewById(R.id.inviteLinksAdd).performClick();
		idle();
		AlertDialog d = RequestFixtures.latestAlert();
		EditText label = d.findViewById(R.id.inviteLabelInput);
		assertNotNull(label);
		label.setText("Work");
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		db.runAll();
		verify(requests).createPermanentLink("Work");
		assertEquals("You can have up to 5 links. Remove one first.",
				ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void aLabelLongerThan32CharactersIsRefused() throws Exception {
		open();
		root.findViewById(R.id.inviteLinksAdd).performClick();
		idle();
		AlertDialog d = RequestFixtures.latestAlert();
		EditText label = d.findViewById(R.id.inviteLabelInput);
		label.setText("123456789012345678901234567890123");
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		db.runAll();
		verify(requests, never()).createPermanentLink(anyString());
		assertTrue(d.isShowing());
	}

	@Test
	public void withdrawAsksFirst() throws Exception {
		links.add(permanent(1, "Support", 0, 0, true));
		open();
		rows().getChildAt(0).findViewById(R.id.inviteLinkWithdraw)
				.performClick();
		idle();
		verify(requests, never()).withdrawPermanentLink(any());
		AlertDialog d = RequestFixtures.latestAlert();
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		db.runAll();
		verify(requests).withdrawPermanentLink(linkId(1));
	}

	@Test
	public void resetIsARedTextButtonAtTheBottomOfTheZeroIdSection() {
		open();
		com.google.android.material.button.MaterialButton reset =
				root.findViewById(R.id.zeroIdResetButton);
		assertEquals("Reset ZerionID", reset.getText().toString());
		assertEquals(host.getColor(R.color.zerion_destructive_text),
				reset.getCurrentTextColor());
		ViewGroup section = (ViewGroup) reset.getParent();
		int at = section.indexOfChild(reset);
		assertTrue(at > section.indexOfChild(root.findViewById(
				R.id.zeroIdKeepReceivingQuiet)));
		assertEquals(R.id.blockedSection, section.getChildAt(at + 1).getId());
	}

	@Test
	public void resetExplainsWhatHappensBeforeResetting() throws Exception {
		open();
		root.findViewById(R.id.zeroIdResetButton).performClick();
		idle();
		AlertDialog d = RequestFixtures.latestAlert();
		TextView message = d.findViewById(android.R.id.message);
		assertNotNull(message);
		assertEquals("Your old ZerionID stops working. Blocks made on it do "
				+ "not carry over.", message.getText().toString());
		verify(requests, never()).resetZeroId();
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		db.runAll();
		verify(requests).resetZeroId();
	}

	@Test
	public void blockedSendersCanBeUnblocked() throws Exception {
		links.add(permanent(1, "Support", 0, 1, true));
		byte[] tag = new byte[32];
		tag[0] = 7;
		when(requests.getBlockedSenders(linkId(1)))
				.thenReturn(Collections.singletonList(tag));
		open();
		ViewGroup blocked = root.findViewById(R.id.blockedContainer);
		assertEquals(1, blocked.getChildCount());
		assertEquals(View.VISIBLE,
				root.findViewById(R.id.blockedSection).getVisibility());
		String t = RequestFixtures.allText(blocked);
		assertTrue(t, t.contains("Blocked sender 1, via Support"));
		blocked.getChildAt(0).findViewById(R.id.blockedSenderUnblock)
				.performClick();
		db.runAll();
		verify(requests).unblock(linkId(1), tag);
	}

	@Test
	public void theBlockedSectionIsHiddenWhenNobodyIsBlocked() {
		links.add(permanent(1, "Support", 0, 0, true));
		open();
		assertEquals(View.GONE,
				root.findViewById(R.id.blockedSection).getVisibility());
		String all = RequestFixtures.allText(root);
		assertFalse(all, all.contains("Blocked senders"));
	}

	private static String date(long at) {
		return android.text.format.DateUtils.formatDateTime(
				androidx.test.core.app.ApplicationProvider.getApplicationContext(),
				at, android.text.format.DateUtils.FORMAT_SHOW_DATE
						| android.text.format.DateUtils.FORMAT_SHOW_YEAR);
	}

	private static String dateTime(long at) {
		return android.text.format.DateUtils.formatDateTime(
				androidx.test.core.app.ApplicationProvider.getApplicationContext(),
				at, android.text.format.DateUtils.FORMAT_SHOW_DATE
						| android.text.format.DateUtils.FORMAT_SHOW_YEAR
						| android.text.format.DateUtils.FORMAT_SHOW_TIME);
	}

	@Test
	public void aPausedLinkSaysSoAndOffersResumeAndRemove() throws Exception {
		long until = System.currentTimeMillis() + 3_600_000L;
		links.add(new ContactLinkInfo(linkId(4), ContactLink.KIND_PERMANENT,
				ContactLink.STATE_ACTIVE, "Support", "zerion://link4", 0, 0,
				false, 0, 0, false, until));
		links.add(permanent(1, "Press", 0, 0, true));
		notices.add(new FloodNotice(linkId(4), ContactLink.KIND_PERMANENT,
				"Support", until - 3_600_000L, until));
		open();
		assertEquals(2, rows().getChildCount());
		View paused = rows().getChildAt(0);
		String text = RequestFixtures.allText(paused);
		assertTrue(text, text.contains("Support"));
		assertTrue(text, text.contains("Too many requests. Paused until "
				+ dateTime(until)));
		assertFalse(text, text.contains("Not receiving right now"));
		assertEquals(View.VISIBLE,
				paused.findViewById(R.id.inviteLinkResume).getVisibility());
		assertEquals(View.VISIBLE,
				paused.findViewById(R.id.inviteLinkWithdraw).getVisibility());
		assertEquals(View.GONE,
				paused.findViewById(R.id.inviteLinkShare).getVisibility());
		assertEquals(View.GONE,
				paused.findViewById(R.id.inviteLinkQr).getVisibility());
		View other = rows().getChildAt(1);
		assertEquals(View.GONE,
				other.findViewById(R.id.inviteLinkResume).getVisibility());
		assertEquals(View.VISIBLE,
				other.findViewById(R.id.inviteLinkShare).getVisibility());
		assertTrue(RequestFixtures.allText(other)
				.contains("Receiving requests"));
		assertEquals("no separate notice for a paused link", 0,
				((ViewGroup) root.findViewById(R.id.inviteLinkNotices))
						.getChildCount());
		paused.findViewById(R.id.inviteLinkResume).performClick();
		db.runAll();
		verify(requests).resumePermanentLink(linkId(4));
		verify(requests, never()).dismissFloodNotice(any());
	}

	@Test
	public void aZeroIdResetAfterAFloodShowsANoticeInItsCard()
			throws Exception {
		long at = System.currentTimeMillis() - 60_000L;
		notices.add(new FloodNotice(linkId(5), ContactLink.KIND_ZERO_ID, null,
				at));
		open();
		ViewGroup card = root.findViewById(R.id.zeroIdNotices);
		assertEquals(1, card.getChildCount());
		String text = RequestFixtures.allText(card);
		assertTrue(text, text.contains("Too many requests. Reset on "
				+ date(at)));
		assertEquals(0, ((ViewGroup) root.findViewById(R.id.inviteLinkNotices))
				.getChildCount());
		card.getChildAt(0).findViewById(R.id.linkNoticeDismiss).performClick();
		db.runAll();
		verify(requests).dismissFloodNotice(linkId(5));
	}

	private static final String LIMITS = "Anyone with your link can see "
			+ "when your phone is online and send you requests. Anyone can "
			+ "make a link that looks official, so get support links from a "
			+ "source you trust and compare the safety code. If a link gets "
			+ "too many requests, it pauses for a while.";
	private static final String ZERO_ID_MORE = "Your ZerionID gets requests "
			+ "for 30 days after you last share it in Zerion. Sharing it again "
			+ "starts the count over. While it gets requests, anyone who has "
			+ "it can see when your phone is online. Zerion cannot see shares "
			+ "outside the app. Blocks stay with the ZerionID they were made "
			+ "on.";

	@Test
	public void theScreenSaysTheZeroIdRotatesAndWhatLinksAreFor() {
		open();
		String all = RequestFixtures.allText(root);
		assertTrue(all, all.contains("It changes after each contact you "
				+ "accept with it."));
		assertTrue(all, all.contains("Invite links stay the same until you "
				+ "remove them. Good for support."));
		assertFalse(all, all.contains(LIMITS));
		assertFalse(all, all.contains(ZERO_ID_MORE));
	}

	@Test
	public void moreInfoOpensAndClosesTheLinkLimits() {
		open();
		TextView toggle = root.findViewById(R.id.inviteLinksMoreInfo);
		View limits = root.findViewById(R.id.inviteLinksLimits);
		assertEquals("More info", toggle.getText().toString());
		assertEquals(View.GONE, limits.getVisibility());
		toggle.performClick();
		assertEquals(View.VISIBLE, limits.getVisibility());
		assertEquals("Less info", toggle.getText().toString());
		assertTrue(RequestFixtures.allText(root).contains(LIMITS));
		toggle.performClick();
		assertEquals(View.GONE, limits.getVisibility());
		assertEquals("More info", toggle.getText().toString());
	}

	@Test
	public void moreInfoOpensTheZeroIdDetailsWithTheDayCount() {
		open();
		TextView toggle = root.findViewById(R.id.zeroIdMoreInfo);
		TextView more = root.findViewById(R.id.zeroIdMoreText);
		assertEquals(View.GONE, more.getVisibility());
		assertEquals(ZERO_ID_MORE, more.getText().toString());
		toggle.performClick();
		assertEquals(View.VISIBLE, more.getVisibility());
		assertEquals("Less info", toggle.getText().toString());
		assertEquals(View.GONE, root.findViewById(R.id.inviteLinksLimits)
				.getVisibility());
		toggle.performClick();
		assertEquals(View.GONE, more.getVisibility());
	}

	@Test
	public void everyOwnerDisclosureIsVisibleOrBehindMoreInfo() {
		long shared = System.currentTimeMillis() - 86_400_000L;
		links.add(new ContactLinkInfo(linkId(0), ContactLink.KIND_ZERO_ID,
				ContactLink.STATE_ACTIVE, null, "zerion://zero", 0, shared,
				true, 0, 0));
		open();
		root.findViewById(R.id.inviteLinksMoreInfo).performClick();
		root.findViewById(R.id.zeroIdMoreInfo).performClick();
		String all = RequestFixtures.allText(root);
		assertTrue(all, all.contains("can see when your phone is online"));
		assertTrue(all, all.contains("compare the safety code"));
		assertTrue(all, all.contains("too many requests, it pauses"));
		assertTrue(all, all.contains("It changes after each contact"));
		assertTrue(all, all.contains("stay the same until you remove them"));
		assertTrue(all, all.contains("anyone who has it can see when your "
				+ "phone is online"));
		assertTrue(all, all.contains("for 30 days after you last share it"));
		assertTrue(all, all.contains("Receiving requests until"));
	}
}
