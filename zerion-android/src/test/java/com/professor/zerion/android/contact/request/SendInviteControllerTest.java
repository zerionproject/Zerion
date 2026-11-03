package com.professor.zerion.android.contact.request;

import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.OwnLinkChangedException;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.DamagedLinkException;
import org.zerionproject.core.api.contact.request.IncompleteLinkException;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.OutgoingRequestLimitException;
import org.zerionproject.core.api.contact.request.OwnLinkException;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;

import java.util.ArrayList;
import java.util.List;

import static com.professor.zerion.android.contact.request.RequestFixtures.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SendInviteControllerTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String ZERO_ID = "zerion://" + repeat('b', 104)
			+ "?i=" + repeat('c', 56);
	private static final String THEIRS = "zerion://" + repeat('a', 104)
			+ "?i=" + repeat('d', 56);
	private static final String OLD = "zerion://" + repeat('a', 104);
	private static final String NOTICE = "Older link. Both of you paste "
			+ "each other's link, or use Scan QR.";

	private final RequestFixtures.Queue db = new RequestFixtures.Queue();
	private final ContactRequestManager requests =
			mock(ContactRequestManager.class);
	private final ContactManager contacts = mock(ContactManager.class);
	private final List<String> events = new ArrayList<>();
	private RequestFixtures.Host host;
	private View root;
	private SendInviteController controller;

	private static String repeat(char c, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) sb.append(c);
		return sb.toString();
	}

	@Before
	public void setUp() throws Exception {
		ShadowDialog.reset();
		ShadowToast.reset();
		IdentityManager identity = mock(IdentityManager.class);
		LocalAuthor me = mock(LocalAuthor.class);
		when(me.getName()).thenReturn("Robin");
		when(identity.getLocalAuthor()).thenReturn(me);
		when(requests.getZeroIdLink()).thenReturn(ZERO_ID);
		host = Robolectric.buildActivity(RequestFixtures.Host.class)
				.setup().get();
		host.setContentView(R.layout.fragment_send_invite);
		root = host.findViewById(android.R.id.content);
		controller = new SendInviteController(host, root, requests,
				identity, db, new SendInviteController.Listener() {
			@Override
			public void onRequestSaved() {
				events.add("saved");
			}

			@Override
			public void onMutualAdded() {
				events.add("mutual");
			}

			@Override
			public void onScanChosen() {
				events.add("scan");
			}

			@Override
			public void onInviteLinksChosen() {
				events.add("links");
			}
		});
		controller.start();
		db.runAll();
	}

	private <T extends View> T view(int id) {
		T v = root.findViewById(id);
		assertNotNull(v);
		return v;
	}

	private void type(int id, String text) {
		EditText e = view(id);
		e.setText(text);
		db.runAll();
	}

	private String linkError() {
		CharSequence e = this.<TextInputLayout>view(R.id.inviteLinkLayout)
				.getError();
		return e == null ? "" : e.toString();
	}

	private void send() {
		view(R.id.inviteSendButton).performClick();
		db.runAll();
	}

	@Test
	public void theAddContactScreenHasExactlyTwoCards() {
		host.setContentView(R.layout.fragment_add_contact_chooser);
		View chooser = host.findViewById(android.R.id.content);
		List<CardView> cards = new ArrayList<>();
		findCards(chooser, cards);
		assertEquals(2, cards.size());
		assertEquals("Add contact", cardTitle(cards.get(0)));
		assertEquals("Scan QR", cardTitle(cards.get(1)));
		String text = RequestFixtures.allText(chooser);
		assertFalse(text, text.contains("Pair nearby"));
		assertFalse(text, text.contains("Share a Link"));
		assertFalse(text, text.contains("Choose how to add a contact"));
		assertTrue(text, text.contains("Use their ZerionID or link. They can "
				+ "answer later."));
		assertTrue(text, text.contains("Scan each other's code in person. "
				+ "Also works without internet."));
	}

	@Test
	public void theScreenTitleMatchesTheCard() {
		assertEquals("Add contact", host.getString(R.string.send_invite_title));
		assertEquals(host.getString(R.string.add_contact_send_invite_title),
				host.getString(R.string.send_invite_title));
	}

	@Test
	public void theNameAndNoteFieldsAppearOnlyAfterALinkIsPasted()
			throws Exception {
		assertEquals(View.GONE, view(R.id.inviteAliasLayout).getVisibility());
		assertEquals(View.GONE,
				view(R.id.inviteShownNameLayout).getVisibility());
		assertEquals(View.GONE, view(R.id.inviteNoteLayout).getVisibility());
		when(requests.parseLink(THEIRS)).thenReturn(
				link(InviteLink.Flow.REQUEST));
		type(R.id.inviteLinkInput, THEIRS);
		assertEquals(View.VISIBLE,
				view(R.id.inviteAliasLayout).getVisibility());
		assertEquals(View.VISIBLE,
				view(R.id.inviteShownNameLayout).getVisibility());
		assertEquals(View.VISIBLE, view(R.id.inviteNoteLayout).getVisibility());
		type(R.id.inviteLinkInput, "");
		assertEquals(View.GONE, view(R.id.inviteAliasLayout).getVisibility());
		assertEquals(View.GONE,
				view(R.id.inviteShownNameLayout).getVisibility());
		assertEquals(View.GONE, view(R.id.inviteNoteLayout).getVisibility());
	}

	@Test
	public void aScannedLinkAlsoShowsTheFields() throws Exception {
		when(requests.parseLink(THEIRS)).thenReturn(
				link(InviteLink.Flow.REQUEST));
		controller.setLink(THEIRS);
		db.runAll();
		assertEquals(View.VISIBLE,
				view(R.id.inviteAliasLayout).getVisibility());
		assertEquals(View.VISIBLE, view(R.id.inviteNoteLayout).getVisibility());
	}

	@Test
	public void textWithoutALinkKeepsTheFieldsHidden() {
		type(R.id.inviteLinkInput, "hello");
		assertEquals(View.GONE, view(R.id.inviteAliasLayout).getVisibility());
		assertEquals(View.GONE, view(R.id.inviteNoteLayout).getVisibility());
	}

	@Test
	public void sendingWithoutALinkAsksForOne() throws Exception {
		send();
		assertEquals("Paste a ZerionID or link", linkError());
		verify(requests, never()).sendRequest(any(), any(), any(), any(),
				anyBoolean());
	}

	@Test
	public void theNoteInfoIsTheHelperTextOfTheNoteField() {
		TextInputLayout note = view(R.id.inviteNoteLayout);
		assertEquals("They see it when they open your request.",
				String.valueOf(note.getHelperText()));
	}

	private static String cardTitle(CardView card) {
		String all = RequestFixtures.allText(card);
		return all.substring(0, all.indexOf('\n'));
	}

	@Test
	public void theScanScreensSayWhichModeIsActiveAndOfferNoSeparateButton() {
		host.setContentView(R.layout.fragment_qr_exchange);
		View qr = host.findViewById(android.R.id.content);
		assertEquals("Scan each other's code", ((TextView) qr.findViewById(
				R.id.qrModeText)).getText().toString());
		assertFalse(RequestFixtures.allText(qr).contains("Pair nearby"));
		host.setContentView(R.layout.fragment_add_nearby_contact);
		View nearby = host.findViewById(android.R.id.content);
		assertEquals("No internet: pairing nearby over Bluetooth",
				((TextView) nearby.findViewById(R.id.nearbyModeText))
						.getText().toString());
	}

	@Test
	public void theZeroIdWindowAndTheLimitsComeFromTheCoreConstants() {
		TextView info = view(R.id.zeroIdInfo);
		assertTrue(info.getText().toString(), info.getText().toString()
				.contains("It works for 30 days after you last share it in "
						+ "Zerion."));
		assertEquals(30, SendInviteController.zeroIdDays());
	}

	private static void findCards(View v, List<CardView> out) {
		if (v instanceof CardView) out.add((CardView) v);
		if (v instanceof android.view.ViewGroup) {
			android.view.ViewGroup g = (android.view.ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				findCards(g.getChildAt(i), out);
			}
		}
	}

	@Test
	public void theZeroIdIsShownAndYourNameIsPrefilledWithoutCountingAsShared()
			throws Exception {
		assertEquals(ZERO_ID,
				this.<TextView>view(R.id.zeroIdView).getText().toString());
		assertEquals("Robin", this.<EditText>view(R.id.inviteShownNameInput)
				.getText().toString());
		verify(requests, never()).zeroIdShared();
	}

	@Test
	public void shareCopyAndShowQrEachCountAsSharingTheZeroId()
			throws Exception {
		view(R.id.zeroIdShareButton).performClick();
		db.runAll();
		view(R.id.zeroIdCopyButton).performClick();
		db.runAll();
		view(R.id.zeroIdQrButton).performClick();
		db.runAll();
		verify(requests, times(3)).zeroIdShared();
		assertNotNull(RequestFixtures.latestAlert()
				.findViewById(R.id.linkQrImage));
	}

	@Test
	public void aRequestLinkSendsARequestWithNameAndNote() throws Exception {
		when(requests.parseLink(THEIRS)).thenReturn(
				link(InviteLink.Flow.REQUEST));
		type(R.id.inviteLinkInput, "Add me: " + THEIRS);
		assertEquals(SendInviteController.Mode.REQUEST, controller.getMode());
		assertEquals(View.VISIBLE, view(R.id.inviteNoteLayout).getVisibility());
		assertEquals("Send request", this.<Button>view(R.id.inviteSendButton)
				.getText().toString());
		type(R.id.inviteAliasInput, "Sam");
		type(R.id.inviteNoteInput, "Hi, from the club");
		send();
		verify(requests).sendRequest(THEIRS, "Sam", "Robin",
				"Hi, from the club", false);
		assertEquals(1, events.size());
		assertEquals("saved", events.get(0));
	}

	@Test
	public void reAddModeSendsAReconnectRequestAndSaysSo() throws Exception {
		when(requests.parseLink(THEIRS)).thenReturn(
				link(InviteLink.Flow.REQUEST));
		controller.setReAdd(true);
		TextView notice = view(R.id.inviteNotice);
		assertEquals(View.GONE, notice.getVisibility());
		type(R.id.inviteLinkInput, THEIRS);
		assertEquals(View.VISIBLE, notice.getVisibility());
		assertTrue(notice.getText().toString(), notice.getText().toString()
				.contains("tells them you are already their contact"));
		type(R.id.inviteAliasInput, "Sam");
		send();
		verify(requests).sendRequest(THEIRS, "Sam", "Robin", null, true);
	}

	@Test
	public void anEmptyNoteIsSentAsNoNote() throws Exception {
		when(requests.parseLink(THEIRS)).thenReturn(
				link(InviteLink.Flow.REQUEST));
		type(R.id.inviteLinkInput, THEIRS);
		type(R.id.inviteAliasInput, "Sam");
		send();
		verify(requests).sendRequest(THEIRS, "Sam", "Robin", null, false);
	}

	@Test
	public void theNoteHasA200CharacterCounterAndRefusesMore() {
		TextInputLayout note = view(R.id.inviteNoteLayout);
		assertTrue(note.isCounterEnabled());
		assertEquals(200, note.getCounterMaxLength());
		assertEquals(0, SendInviteController.noteProblem(repeat('x', 200)));
		assertEquals(R.string.send_invite_note_too_long,
				SendInviteController.noteProblem(repeat('x', 201)));
	}

	@Test
	public void anOldLinkExplainsBothPhonesMustPasteAndUsesTheFallback()
			throws Exception {
		when(requests.parseLink(OLD)).thenReturn(
				link(InviteLink.Flow.MUTUAL_V1));
		type(R.id.inviteLinkInput, OLD);
		assertEquals(SendInviteController.Mode.MUTUAL_V1, controller.getMode());
		TextView notice = view(R.id.inviteNotice);
		assertEquals(View.VISIBLE, notice.getVisibility());
		assertEquals(NOTICE, notice.getText().toString());
		assertEquals(View.GONE, view(R.id.inviteNoteLayout).getVisibility());
		assertEquals(View.GONE,
				view(R.id.inviteShownNameLayout).getVisibility());
		assertEquals("Add contact", this.<Button>view(R.id.inviteSendButton)
				.getText().toString());
		type(R.id.inviteAliasInput, "Sam");
		send();
		InOrder order = inOrder(requests);
		order.verify(requests, times(2)).parseLink(OLD);
		order.verify(requests).addMutualFromInvite(OLD, "Sam", null, false);
		verify(requests, never()).sendRequest(any(), any(), any(), any(),
				anyBoolean());
		assertEquals("mutual", events.get(0));
	}

	@Test
	public void theFallbackIsBoundToTheZeroIdSharedOnThisScreen()
			throws Exception {
		when(requests.parseLink(OLD)).thenReturn(
				link(InviteLink.Flow.MUTUAL_V1));
		view(R.id.zeroIdCopyButton).performClick();
		type(R.id.inviteLinkInput, OLD);
		type(R.id.inviteAliasInput, "Sam");
		send();
		verify(requests).addMutualFromInvite(OLD, "Sam", ZERO_ID, false);
	}

	@Test
	public void theFallbackIsNeverCalledWhenTheLinkDoesNotParse()
			throws Exception {
		when(requests.parseLink(OLD)).thenThrow(new OwnLinkException());
		type(R.id.inviteLinkInput, OLD);
		type(R.id.inviteAliasInput, "Sam");
		send();
		verify(requests, never()).addMutualFromInvite(anyString(),
				anyString(), any(), anyBoolean());
		assertEquals("This is your own link", linkError());
	}

	@Test
	public void aClassicalLinkIsRefusedInSendInvite() throws Exception {
		String classical = "zerion://" + repeat('a', 53);
		when(requests.parseLink(classical)).thenReturn(
				link(InviteLink.Flow.MUTUAL_CLASSICAL));
		type(R.id.inviteLinkInput, classical);
		assertEquals("Older link. Ask them to update Zerion, or use Scan QR.",
				linkError());
		type(R.id.inviteAliasInput, "Sam");
		send();
		verify(contacts, never()).addPendingContactBoundToSharedLink(any(),
				any(), anyBoolean(), anyBoolean());
		verify(requests, never()).addMutualFromInvite(anyString(),
				anyString(), any(), anyBoolean());
		verify(requests, never()).sendRequest(any(), any(), any(), any(),
				anyBoolean());
	}

	@Test
	public void aResetZeroIdAfterSharingAsksToShareAgain() throws Exception {
		when(requests.parseLink(OLD)).thenReturn(
				link(InviteLink.Flow.MUTUAL_V1));
		when(requests.addMutualFromInvite(any(), any(), any(), anyBoolean()))
				.thenThrow(new OwnLinkChangedException());
		type(R.id.inviteLinkInput, OLD);
		type(R.id.inviteAliasInput, "Sam");
		send();
		assertEquals("Your ZerionID changed after you shared it. Share the "
				+ "new one.", ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void sharingFetchesTheCurrentZeroIdAndActsOnIt() throws Exception {
		String reset = "zerion://" + repeat('e', 104) + "?i="
				+ repeat('f', 56);
		when(requests.getZeroIdLink()).thenReturn(reset);
		org.mockito.Mockito.clearInvocations(requests);
		view(R.id.zeroIdCopyButton).performClick();
		db.runAll();
		InOrder order = inOrder(requests);
		order.verify(requests).getZeroIdLink();
		order.verify(requests).zeroIdShared();
		assertEquals(reset,
				this.<TextView>view(R.id.zeroIdView).getText().toString());
		when(requests.parseLink(OLD)).thenReturn(
				link(InviteLink.Flow.MUTUAL_V1));
		type(R.id.inviteLinkInput, OLD);
		type(R.id.inviteAliasInput, "Sam");
		send();
		verify(requests).addMutualFromInvite(OLD, "Sam", reset, false);
	}

	@Test
	public void aRequestSavedWhilePausedSaysWhenItIsSent() throws Exception {
		when(requests.parseLink(THEIRS)).thenReturn(
				link(InviteLink.Flow.REQUEST));
		when(requests.isPausedAfterRestore()).thenReturn(true);
		type(R.id.inviteLinkInput, THEIRS);
		type(R.id.inviteAliasInput, "Sam");
		send();
		assertEquals("Saved. Confirm this is your only device to send it.",
				ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void theNoteCounterCountsCharactersNotCodeUnits() {
		StringBuilder faces = new StringBuilder();
		for (int i = 0; i < 150; i++) faces.appendCodePoint(0x1F600);
		EditText note = view(R.id.inviteNoteInput);
		note.setText(faces);
		assertEquals(150, SendInviteController.codePoints(note.getText()));
		assertEquals(0, SendInviteController.noteProblem(faces.toString()));
	}

	@Test
	public void theZeroIdHasASpokenLabel() {
		assertEquals("Your ZerionID link", String.valueOf(
				view(R.id.zeroIdView).getContentDescription()));
	}

	private String errorFor(FormatException e) throws Exception {
		when(requests.parseLink(THEIRS)).thenThrow(e);
		type(R.id.inviteLinkInput, THEIRS);
		return linkError();
	}

	@Test
	public void ownLinksAreRefused() throws Exception {
		assertEquals("This is your own link",
				errorFor(new OwnLinkException()));
	}

	@Test
	public void damagedLinksAreRefused() throws Exception {
		assertEquals("This link is damaged",
				errorFor(new DamagedLinkException()));
	}

	@Test
	public void incompleteLinksAreRefused() throws Exception {
		assertEquals("This link is incomplete",
				errorFor(new IncompleteLinkException()));
	}

	@Test
	public void linksFromANewerVersionAskForAnUpgrade() throws Exception {
		assertEquals(host.getString(R.string.unsupported_link),
				errorFor(new UnsupportedVersionException(false)));
	}

	@Test
	public void textWithoutALinkIsInvalid() {
		type(R.id.inviteLinkInput, "hello");
		assertEquals("Invalid link", linkError());
	}

	@Test
	public void tooManyOpenRequestsIsExplained() throws Exception {
		when(requests.parseLink(THEIRS)).thenReturn(
				link(InviteLink.Flow.REQUEST));
		when(requests.sendRequest(any(), any(), any(), any(), anyBoolean()))
				.thenThrow(new OutgoingRequestLimitException());
		type(R.id.inviteLinkInput, THEIRS);
		type(R.id.inviteAliasInput, "Sam");
		send();
		assertEquals("Already sent to this link, or 20 requests are still "
				+ "open. Cancel one or wait.",
				ShadowToast.getTextOfLatestToast());
		assertTrue(events.isEmpty());
		assertTrue(view(R.id.inviteSendButton).isEnabled());
	}

	@Test
	public void aMissingNameStopsTheRequest() throws Exception {
		when(requests.parseLink(THEIRS)).thenReturn(
				link(InviteLink.Flow.REQUEST));
		type(R.id.inviteLinkInput, THEIRS);
		send();
		verify(requests, never()).sendRequest(any(), any(), any(), any(),
				anyBoolean());
		assertEquals("Enter their name",
				String.valueOf(this.<TextInputLayout>view(
						R.id.inviteAliasLayout).getError()));
	}

	@Test
	public void theInviteLinksAndScanButtonsReachTheirScreens() {
		view(R.id.inviteLinksButton).performClick();
		view(R.id.inviteScanButton).performClick();
		assertEquals("links", events.get(0));
		assertEquals("scan", events.get(1));
	}

	@Test
	public void aPausedCopyShowsTheBanner() throws Exception {
		assertEquals(View.GONE, view(R.id.invitesPausedBanner).getVisibility());
		when(requests.isPausedAfterRestore()).thenReturn(true);
		controller.start();
		db.runAll();
		TextView banner = view(R.id.invitesPausedBanner);
		assertEquals(View.VISIBLE, banner.getVisibility());
		assertEquals("Invites paused. Tap to confirm this is your only "
				+ "device.", banner.getText().toString());
		verify(contacts, never()).addPendingContactBoundToSharedLink(any(),
				any(), anyBoolean(), anyBoolean());
	}

	@Test
	public void theZeroIdCardSaysItChangesAfterEveryAccept() {
		TextView info = view(R.id.zeroIdRotatesInfo);
		assertEquals("It changes after each contact you accept with it.",
				info.getText().toString());
		assertEquals(View.VISIBLE, info.getVisibility());
	}
}
