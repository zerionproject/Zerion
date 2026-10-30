package com.professor.zerion.android.contact.request;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.IncomingRequestInfo;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.contact.request.OutgoingRequestInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.professor.zerion.android.contact.request.RequestFixtures.id;
import static com.professor.zerion.android.contact.request.RequestFixtures.idle;
import static com.professor.zerion.android.contact.request.RequestFixtures.incoming;
import static com.professor.zerion.android.contact.request.RequestFixtures.outgoing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RequestsControllerTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long DAY = 86_400_000L;
	private static final long NOW = 1_800_000_000_000L;

	private final RequestFixtures.Queue db = new RequestFixtures.Queue();
	private final ContactRequestManager requests =
			mock(ContactRequestManager.class);
	private final ContactManager contacts = mock(ContactManager.class);
	private final List<IncomingRequestInfo> in = new ArrayList<>();
	private final List<OutgoingRequestInfo> out = new ArrayList<>();
	private final List<Pair<PendingContact, PendingContactState>> pending =
			new ArrayList<>();
	private RequestFixtures.Host host;
	private View root;
	private RequestsController controller;

	@Before
	public void setUp() throws Exception {
		ShadowDialog.reset();
		ShadowToast.reset();
		when(requests.getIncomingRequests()).thenReturn(in);
		when(requests.getOutgoingRequests()).thenReturn(out);
		when(contacts.getPendingContacts()).thenReturn(pending);
		host = Robolectric.buildActivity(RequestFixtures.Host.class)
				.setup().get();
		host.setContentView(R.layout.activity_requests);
		root = host.findViewById(R.id.requestsRoot);
	}

	private long clock = NOW;

	private void open(int tab) {
		controller = new RequestsController(host, root, requests, contacts,
				db, tab, () -> clock);
		controller.onStart();
		controller.reload();
		db.runAll();
		layout();
	}

	private void layout() {
		RecyclerView list = root.findViewById(R.id.requestsList);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				2000, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 2000);
		idle();
	}

	private View row(int position) {
		RecyclerView list = root.findViewById(R.id.requestsList);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no row " + position, h);
		return h.itemView;
	}

	private int rows() {
		RecyclerView list = root.findViewById(R.id.requestsList);
		RecyclerView.Adapter<?> a = list.getAdapter();
		return a == null ? 0 : a.getItemCount();
	}

	private static String text(View row) {
		return RequestFixtures.allText(row);
	}

	private PendingContact pendingContact(int n, String alias) {
		return new PendingContact(new PendingContactId(new byte[] {(byte) n,
				0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
				0, 0, 0, 0, 0, 0, 0, 0, 0, 0}), mock(org.zerionproject.core.api.crypto
				.PublicKey.class), alias, NOW);
	}

	@Test
	public void receivedRowsShowNameOrUnnamedAndTheLinkButNeverTheNote() {
		in.add(incoming(1, null, "secret note", IncomingRequest.STATE_RECEIVED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		in.add(incoming(2, "Alex", "another note",
				IncomingRequest.STATE_OPENED, ContactLink.KIND_PERMANENT,
				"Work", NOW - DAY, NOW - DAY, null));
		open(RequestsController.TAB_RECEIVED);
		assertEquals(2, rows());
		String first = text(row(0));
		String second = text(row(1));
		assertTrue(first, first.contains("No name"));
		assertTrue(first, first.contains("via your ZerionID"));
		assertTrue(second, second.contains("Alex"));
		assertTrue(second, second.contains("via Work"));
		assertFalse(first + second, (first + second).contains("note"));
	}

	@Test
	public void openingARequestMarksItOpenedAndShowsTheNoteAndClockHint()
			throws Exception {
		in.add(incoming(1, "Alex", "See you at https://example.org",
				IncomingRequest.STATE_RECEIVED, ContactLink.KIND_ZERO_ID, null,
				NOW - 5 * DAY, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		db.runAll();
		verify(requests).markOpened(id(1));
		AlertDialog d = RequestFixtures.latestAlert();
		TextView note = d.findViewById(R.id.requestDetailNote);
		assertNotNull(note);
		assertEquals(View.VISIBLE, note.getVisibility());
		assertEquals("See you at https://example.org", note.getText().toString());
		assertEquals(0, note.getAutoLinkMask());
		assertTrue(note.isTextSelectable());
		assertEquals(View.VISIBLE,
				d.findViewById(R.id.requestDetailClock).getVisibility());
	}

	@Test
	public void theClockHintStaysHiddenWhenClocksAgree() {
		in.add(incoming(1, null, null, IncomingRequest.STATE_RECEIVED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		AlertDialog d = RequestFixtures.latestAlert();
		assertEquals(View.GONE,
				d.findViewById(R.id.requestDetailClock).getVisibility());
		assertEquals(View.GONE,
				d.findViewById(R.id.requestDetailNote).getVisibility());
		TextView name = d.findViewById(R.id.requestDetailName);
		assertEquals("No name", name.getText().toString());
	}

	@Test
	public void acceptAsksForTheNameWithTheRequestNamePrefilled()
			throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		idle();
		AlertDialog ask = RequestFixtures.latestAlert();
		EditText name = ask.findViewById(R.id.requestAcceptName);
		assertNotNull(name);
		assertEquals("Alex", name.getText().toString());
		name.setText("Alex from work");
		ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		db.runAll();
		verify(requests).accept(id(1), "Alex from work");
	}

	@Test
	public void aReservedNameRefusedByAcceptShowsTheNameError()
			throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		when(requests.accept(any(), any()))
				.thenThrow(new IllegalArgumentException());
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		idle();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		db.runAll();
		assertEquals(host.getString(R.string.name_reserved),
				ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void declineWaitsFiveSecondsForUndo() throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_NEGATIVE)
				.performClick();
		idle();
		db.runAll();
		assertEquals(0, rows());
		verify(requests, never()).decline(any());
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_900));
		db.runAll();
		verify(requests, never()).decline(any());
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
		db.runAll();
		verify(requests).decline(id(1));
	}

	@Test
	public void undoKeepsTheRequest() throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_NEGATIVE)
				.performClick();
		idle();
		View undo = findSnackbarAction();
		undo.performClick();
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
		db.runAll();
		verify(requests, never()).decline(any());
		assertEquals(1, rows());
	}

	@Test
	public void undoAfterTheDeclineRanSaysItIsAlreadyDeclined()
			throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_NEGATIVE)
				.performClick();
		idle();
		View undo = findSnackbarAction();
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100));
		db.runAll();
		verify(requests).decline(id(1));
		undo.performClick();
		idle();
		assertEquals("Already declined", ShadowToast.getTextOfLatestToast());
		verify(requests).decline(any());
	}

	@Test
	public void leavingTheScreenDeclinesAtOnceAndHidesTheUndo()
			throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_NEGATIVE)
				.performClick();
		idle();
		findSnackbarAction();
		controller.flushDeclines();
		db.runAll();
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
		verify(requests).decline(id(1));
		View bar = root.getRootView().findViewById(
				com.google.android.material.R.id.snackbar_action);
		org.junit.Assert.assertTrue("the undo bar is gone",
				bar == null || !bar.isShown());
	}

	@Test
	public void theNoteIsNeverOfferedAsALinkOrAction() throws Exception {
		in.add(incoming(1, "Alex", "call +31 6 1234 5678",
				IncomingRequest.STATE_OPENED, ContactLink.KIND_ZERO_ID, null,
				NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		TextView note = RequestFixtures.latestAlert()
				.findViewById(R.id.requestDetailNote);
		assertEquals(android.view.textclassifier.TextClassifier.NO_OP,
				note.getTextClassifier());
		assertTrue(note.isTextSelectable());
	}

	@Test
	public void theRequestScreensCannotBeCaptured() throws Exception {
		for (String name : new String[] {"RequestsActivity",
				"InviteLinksActivity"}) {
			String src = new String(java.nio.file.Files.readAllBytes(
					java.nio.file.Paths.get("src/main/java/com/professor/"
							+ "zerion/android/contact/request/" + name
							+ ".java")), java.nio.charset.StandardCharsets.UTF_8);
			int secure = src.indexOf("FLAG_SECURE");
			int content = src.indexOf("setContentView");
			assertTrue(name, secure > 0 && secure < content);
		}
	}

	@Test
	public void anAcceptThatIsNoLongerPossibleSaysItExpired()
			throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		when(requests.accept(any(), any()))
				.thenThrow(new IllegalStateException());
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		idle();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		db.runAll();
		assertEquals("Request expired",
				ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void theBannerIsCheckedAgainOnEveryReload() throws Exception {
		open(RequestsController.TAB_RECEIVED);
		View banner = root.findViewById(R.id.invitesPausedBanner);
		assertEquals(View.GONE, banner.getVisibility());
		when(requests.isPausedAfterRestore()).thenReturn(true);
		controller.reload();
		db.runAll();
		assertEquals(View.VISIBLE, banner.getVisibility());
		when(requests.isPausedAfterRestore()).thenReturn(false);
		controller.reload();
		db.runAll();
		assertEquals(View.GONE, banner.getVisibility());
	}

	private View findSnackbarAction() {
		View v = root.getRootView().findViewById(
				com.google.android.material.R.id.snackbar_action);
		assertNotNull("no undo", v);
		assertEquals("Undo", ((TextView) v).getText().toString());
		return v;
	}

	@Test
	public void blockAsksFirstWithThePlainExplanation() throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_NEUTRAL)
				.performClick();
		idle();
		AlertDialog confirm = RequestFixtures.latestAlert();
		TextView message = confirm.findViewById(android.R.id.message);
		assertNotNull(message);
		assertEquals("You will get no more requests from them through your "
				+ "ZerionID.", message.getText().toString());
		verify(requests, never()).block(any());
		confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		db.runAll();
		verify(requests).block(id(1));
	}

	@Test
	public void blockingARequestFromAnInviteLinkNamesThatLink()
			throws Exception {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_PERMANENT, "Work", NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_NEUTRAL)
				.performClick();
		idle();
		TextView message = RequestFixtures.latestAlert()
				.findViewById(android.R.id.message);
		assertNotNull(message);
		assertEquals("You will get no more requests from them through this "
				+ "link: Work.", message.getText().toString());
	}

	@Test
	public void aLinkWithoutALabelGetsTheGeneralBlockText() {
		IncomingRequestInfo r = incoming(1, "Alex", null,
				IncomingRequest.STATE_OPENED, ContactLink.KIND_PERMANENT, null,
				NOW, NOW, null);
		assertEquals("You will get no more requests from them through the "
				+ "link this request came from.",
				RequestsController.blockMessage(host, r));
	}

	@Test
	@Config(qualifiers = "nl")
	public void dutchProgressStatesDoNotReadAsButtons() {
		assertEquals("Contact wordt toegevoegd\u2026",
				host.getString(R.string.adding_contact));
		assertEquals("Wordt geannuleerd\u2026",
				host.getString(R.string.requests_state_withdrawing));
		assertEquals("Wachten tot contact online komt\u2026",
				host.getString(R.string.waiting_for_contact_to_come_online));
		assertEquals("Verzoek geweigerd",
				host.getString(R.string.requests_declined));
		assertEquals("Al geweigerd",
				host.getString(R.string.requests_already_declined));
		assertEquals("Annuleren", host.getString(R.string.cancel));
		assertEquals("Contact toevoegen",
				host.getString(R.string.add_contact_button));
	}

	@Test
	public void anAcceptedRequestThatCouldNotConnectOffersTryAgain()
			throws Exception {
		PendingContact p = pendingContact(9, "Alex");
		pending.add(new Pair<>(p, PendingContactState.FAILED));
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, p.getId()));
		open(RequestsController.TAB_RECEIVED);
		String t = text(row(0));
		assertTrue(t, t.contains("Alex"));
		assertTrue(t, t.contains("Could not connect"));
		row(0).findViewById(R.id.requestAction).performClick();
		db.runAll();
		verify(requests).retryAccepted(id(1));
	}

	@Test
	public void tryAgainOnAGoneRowSaysTheRequestExpired() throws Exception {
		PendingContact p = pendingContact(9, "Alex");
		pending.add(new Pair<>(p, PendingContactState.FAILED));
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, p.getId()));
		doThrow(new IllegalStateException()).when(requests)
				.retryAccepted(any());
		open(RequestsController.TAB_RECEIVED);
		row(0).findViewById(R.id.requestAction).performClick();
		db.runAll();
		assertEquals("Request expired",
				ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void sentRowsShowEachState() {
		out.add(outgoing(1, "Queued one", OutgoingRequest.STATE_QUEUED,
				NOW - 1, 0, null));
		out.add(outgoing(2, "Delivered one", OutgoingRequest.STATE_DELIVERED,
				NOW - 2, NOW, null));
		out.add(outgoing(3, "Added one", OutgoingRequest.STATE_ADDED,
				NOW - 3, NOW, null));
		out.add(outgoing(4, "Silent one", OutgoingRequest.STATE_NO_ANSWER,
				NOW - 4, NOW, null));
		out.add(outgoing(5, "Lost one", OutgoingRequest.STATE_NOT_DELIVERED,
				NOW - 5, 0, null));
		out.add(outgoing(6, "Withdrawn one", OutgoingRequest.STATE_CANCELLED,
				NOW - 6, NOW, null));
		out.add(outgoing(7, "Cancelled one", OutgoingRequest.STATE_CANCELLED,
				NOW - 7, 0, null));
		out.add(outgoing(8, "Going one", OutgoingRequest.STATE_WITHDRAWING,
				NOW - 8, NOW, null));
		open(RequestsController.TAB_SENT);
		assertEquals(8, rows());
		String[][] expected = {
				{"Queued one", "Not sent yet", "Cancel request"},
				{"Delivered one", "Delivered",
						"Cancel request"},
				{"Added one", "Accepted", "Remove"},
				{"Silent one", "No answer", "Remove"},
				{"Lost one", "Not delivered", "Remove"},
				{"Withdrawn one", "Cancelled after delivery", "Remove"},
				{"Cancelled one", "Cancelled", "Remove"},
				{"Going one", "Cancelling\u2026", null}};
		for (int i = 0; i < expected.length; i++) {
			String t = text(row(i));
			for (String s : expected[i]) {
				if (s != null) assertTrue(t, t.contains(s));
			}
			if (expected[i][2] == null) {
				assertEquals(View.GONE, row(i).findViewById(
						R.id.requestAction).getVisibility());
			}
		}
	}

	@Test
	public void aQueuedRequestToAnOverloadedInboxSaysSoInPlainWords() {
		out.add(new OutgoingRequestInfo(new OutgoingRequest(id(1),
				new org.zerionproject.core.api.identity.AuthorId(new byte[32]),
				"zerion://x", "Busy one", "Me", null, null, null, NOW - 1,
				OutgoingRequest.STATE_QUEUED, 3, 0, 0, null, 0), true));
		open(RequestsController.TAB_SENT);
		String t = text(row(0));
		assertTrue(t, t.contains("Their phone is busy. Zerion will try again."));
		assertFalse(t, t.contains("Not sent yet"));
	}

	@Test
	public void cancelAsksFirstAndDismissRemovesAFinishedRow()
			throws Exception {
		out.add(outgoing(1, "Open", OutgoingRequest.STATE_DELIVERED, NOW - 1,
				NOW, null));
		out.add(outgoing(2, "Done", OutgoingRequest.STATE_NO_ANSWER, NOW - 2,
				NOW, null));
		open(RequestsController.TAB_SENT);
		row(0).findViewById(R.id.requestAction).performClick();
		idle();
		verify(requests, never()).cancelRequest(any());
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		db.runAll();
		verify(requests).cancelRequest(id(1));
		row(1).findViewById(R.id.requestAction).performClick();
		db.runAll();
		verify(requests).dismissOutgoing(id(2));
	}

	@Test
	public void aCancelTouchesTheBannerOnlyOnTheMainThread() throws Exception {
		out.add(outgoing(1, "Open", OutgoingRequest.STATE_DELIVERED, NOW - 1,
				NOW, null));
		RequestFixtures.WatchedBanner banner =
				RequestFixtures.watchBanner(root);
		controller = new RequestsController(host, root, requests, contacts,
				new RequestFixtures.Background(), RequestsController.TAB_SENT);
		controller.reload();
		idle();
		layout();
		row(0).findViewById(R.id.requestAction).performClick();
		idle();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		idle();
		verify(requests).cancelRequest(id(1));
		verify(requests, atLeast(2)).isPausedAfterRestore();
		assertEquals(Collections.emptyList(), banner.wrongThread);
	}

	@Test
	public void mutualPendingContactsAppearUnderSentOnly() throws Exception {
		PendingContact mutual = pendingContact(3, "Pat");
		PendingContact owned = pendingContact(4, "Owned");
		pending.add(new Pair<>(mutual, PendingContactState.OFFLINE));
		pending.add(new Pair<>(owned, PendingContactState.OFFLINE));
		out.add(outgoing(1, "Request", OutgoingRequest.STATE_DELIVERED, NOW - 1,
				NOW, owned.getId()));
		open(RequestsController.TAB_SENT);
		assertEquals(2, rows());
		String all = text(row(0)) + text(row(1));
		assertTrue(all, all.contains("Pat"));
		assertFalse(all, all.contains("Owned"));
		TabLayout tabs = root.findViewById(R.id.requestsTabs);
		tabs.getTabAt(RequestsController.TAB_RECEIVED).select();
		idle();
		assertEquals(0, rows());
		assertEquals("No requests yet", ((TextView) root.findViewById(
				R.id.requestsEmpty)).getText().toString());
	}

	@Test
	public void finishedSentRowsSayRemoveAndReceivedOnesKeepDismiss()
			throws Exception {
		out.add(outgoing(1, "Done", OutgoingRequest.STATE_NO_ANSWER, NOW - 1,
				NOW, null));
		in.add(incoming(2, "Alex", null, IncomingRequest.STATE_WITHDRAWN,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_SENT);
		Button sent = row(0).findViewById(R.id.requestAction);
		assertEquals("Remove", sent.getText().toString());
		assertEquals(R.string.requests_remove,
				RequestsController.actionLabel(RequestRow.sent(host, out,
						in, pending, NOW).get(0)));
		TabLayout tabs = root.findViewById(R.id.requestsTabs);
		tabs.getTabAt(RequestsController.TAB_RECEIVED).select();
		idle();
		layout();
		Button received = row(0).findViewById(R.id.requestAction);
		assertEquals("Dismiss", received.getText().toString());
		String t = text(row(0));
		assertTrue(t, t.contains("Cancelled by"));
		received.performClick();
		db.runAll();
		verify(requests).dismissIncoming(id(2));
	}

	@Test
	public void theDetailShowsTheNameCaptionRightUnderTheName() {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_RECEIVED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		AlertDialog d = RequestFixtures.latestAlert();
		TextView name = d.findViewById(R.id.requestDetailName);
		assertNotNull(name);
		ViewGroup parent = (ViewGroup) name.getParent();
		TextView caption = (TextView) parent.getChildAt(
				parent.indexOfChild(name) + 1);
		assertEquals("Name chosen by the sender, not checked.",
				caption.getText().toString());
		assertEquals(View.VISIBLE, caption.getVisibility());
		assertEquals("Accept", d.getButton(AlertDialog.BUTTON_POSITIVE)
				.getText().toString());
		assertEquals("Decline", d.getButton(AlertDialog.BUTTON_NEGATIVE)
				.getText().toString());
		assertTrue(RequestFixtures.allText(parent).contains("After you accept, "
				+ "compare the safety code."));
	}

	@Test
	@Config(qualifiers = "nl")
	public void dutchAcceptAndDeclineReadAsVerbs() {
		assertEquals("Accepteren", host.getString(R.string.accept));
		assertEquals("Weigeren", host.getString(R.string.decline));
		assertEquals("Verwijderen", host.getString(R.string.requests_remove));
	}

	@Test
	public void theTabsAreReceivedAndSent() {
		open(RequestsController.TAB_RECEIVED);
		TabLayout tabs = root.findViewById(R.id.requestsTabs);
		assertEquals(2, tabs.getTabCount());
		assertEquals("Received", String.valueOf(tabs.getTabAt(0).getText()));
		assertEquals("Sent", String.valueOf(tabs.getTabAt(1).getText()));
	}

	@Test
	public void theContactsEntryShowsTheUnopenedCount() throws Exception {
		host.setContentView(R.layout.fragment_contact_list);
		View entry = host.findViewById(R.id.contactRequestsEntry);
		when(requests.getUnopenedCount()).thenReturn(3);
		RequestsEntry e = new RequestsEntry(host, entry, requests, db);
		e.refresh();
		db.runAll();
		TextView badge = host.findViewById(R.id.contactRequestsBadge);
		assertEquals(View.VISIBLE, badge.getVisibility());
		assertEquals("3", badge.getText().toString());
		assertEquals("Contact requests, 3 new requests",
				String.valueOf(entry.getContentDescription()));
		when(requests.getUnopenedCount()).thenReturn(0);
		e.refresh();
		db.runAll();
		assertEquals(View.GONE, badge.getVisibility());
		entry.performClick();
		assertEquals(RequestsActivity.class.getName(), shadowOf(host)
				.getNextStartedActivity().getComponent().getClassName());
	}

	private static boolean progressShown(View row) {
		return row.findViewById(R.id.requestProgress).getVisibility()
				== View.VISIBLE;
	}

	private static String actionText(View row) {
		TextView b = row.findViewById(R.id.requestAction);
		return b.getVisibility() == View.VISIBLE ? b.getText().toString()
				: null;
	}

	@Test
	public void noAnswerYetIsShownFromThreeDaysAfterDelivery()
			throws Exception {
		assertEquals(3 * DAY, RequestRow.NO_ANSWER_YET_MS);
		out.add(outgoing(1, "Fresh", OutgoingRequest.STATE_DELIVERED,
				NOW - 10 * DAY, NOW - 3 * DAY + 1, null));
		out.add(outgoing(2, "Quiet", OutgoingRequest.STATE_DELIVERED,
				NOW - 11 * DAY, NOW - 3 * DAY, null));
		out.add(outgoing(3, "Old", OutgoingRequest.STATE_DELIVERED,
				NOW - 40 * DAY, NOW - 31 * DAY, null));
		out.add(outgoing(4, "Ended", OutgoingRequest.STATE_NO_ANSWER,
				NOW - 50 * DAY, NOW - 33 * DAY, null));
		open(RequestsController.TAB_SENT);
		String fresh = text(row(0));
		String quiet = text(row(1));
		String old = text(row(2));
		String ended = text(row(3));
		assertTrue(fresh, fresh.contains("Delivered"));
		assertFalse(fresh, fresh.contains("No answer"));
		assertTrue(quiet, quiet.contains("No answer yet"));
		assertFalse(quiet, quiet.contains("Delivered"));
		assertEquals("Cancel request", actionText(row(1)));
		assertTrue(old, old.contains("No answer yet"));
		assertTrue(ended, ended.contains("No answer"));
		assertFalse(ended, ended.contains("No answer yet"));
		verify(requests, never()).cancelRequest(any());
	}

	@Test
	public void anAcceptedRequestShowsConnectingWithProgressRightAfterAccept() {
		PendingContact p = pendingContact(9, "Alex");
		pending.add(new Pair<>(p, PendingContactState.WAITING_FOR_CONNECTION));
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, p.getId()));
		clock = NOW + 30_000;
		open(RequestsController.TAB_RECEIVED);
		String t = text(row(0));
		assertTrue(t, t.contains("Connecting…"));
		assertTrue(progressShown(row(0)));
		assertNull(actionText(row(0)));
		clock = NOW + RequestRow.CONNECTING_SHOWN_MS + 1;
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
				RequestRow.CONNECTING_SHOWN_MS));
		db.runAll();
		layout();
		t = text(row(0));
		assertTrue(t, t.contains(
				"Accepted, connects when you are both online"));
		assertFalse(progressShown(row(0)));
	}

	@Test
	public void addingTheContactShowsProgressOnBothSides() {
		PendingContact accepted = pendingContact(9, "Alex");
		PendingContact sentTo = pendingContact(8, "Sam");
		pending.add(new Pair<>(accepted, PendingContactState.ADDING_CONTACT));
		pending.add(new Pair<>(sentTo, PendingContactState.ADDING_CONTACT));
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW - DAY, NOW - DAY,
				accepted.getId()));
		out.add(outgoing(2, "Sam", OutgoingRequest.STATE_DELIVERED, NOW - 1,
				NOW, sentTo.getId()));
		open(RequestsController.TAB_RECEIVED);
		assertTrue(text(row(0)).contains("Adding contact…"));
		assertTrue(progressShown(row(0)));
		controller.selectTab(RequestsController.TAB_SENT);
		layout();
		assertEquals(1, rows());
		assertTrue(text(row(0)), text(row(0)).contains("Adding contact…"));
		assertTrue(progressShown(row(0)));
	}

	@Test
	public void anAcceptedRequestWaitingLongerShowsNoProgress() {
		PendingContact p = pendingContact(9, "Alex");
		pending.add(new Pair<>(p, PendingContactState.OFFLINE));
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, p.getId()));
		clock = NOW + 30_000;
		open(RequestsController.TAB_RECEIVED);
		assertTrue(text(row(0)).contains(
				"Accepted, connects when you are both online"));
		assertFalse(progressShown(row(0)));
	}

	@Test
	public void theReceivedRowStaysAsContactAddedWithOpenChat()
			throws Exception {
		PendingContact p = pendingContact(9, "Alex");
		pending.add(new Pair<>(p, PendingContactState.ADDING_CONTACT));
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, p.getId()));
		open(RequestsController.TAB_RECEIVED);
		in.clear();
		pending.clear();
		controller.reload();
		db.runAll();
		controller.onContactAdded(id(1), new ContactId(42));
		db.runAll();
		layout();
		assertEquals(1, rows());
		String t = text(row(0));
		assertTrue(t, t.contains("Alex"));
		assertTrue(t, t.contains("Contact added"));
		assertFalse(progressShown(row(0)));
		assertEquals("Open chat", actionText(row(0)));
		assertFalse(host.isFinishing());
		assertNull(shadowOf(host).getNextStartedActivity());
		View bar = root.getRootView().findViewById(
				com.google.android.material.R.id.snackbar_text);
		assertNotNull(bar);
		assertEquals("Contact added", ((TextView) bar).getText().toString());
		row(0).findViewById(R.id.requestAction).performClick();
		android.content.Intent i = shadowOf(host).getNextStartedActivity();
		assertEquals(com.professor.zerion.android.conversation
				.ConversationActivity.class.getName(),
				i.getComponent().getClassName());
		assertEquals(42, i.getIntExtra(com.professor.zerion.android
				.conversation.ConversationActivity.CONTACT_ID, -1));
		assertFalse(host.isFinishing());
	}

	@Test
	public void theSnackbarOffersToOpenTheNewChat() {
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		controller.onContactAdded(id(1), new ContactId(7));
		db.runAll();
		View action = root.getRootView().findViewById(
				com.google.android.material.R.id.snackbar_action);
		assertNotNull(action);
		assertEquals("Open chat", ((TextView) action).getText().toString());
		action.performClick();
		android.content.Intent i = shadowOf(host).getNextStartedActivity();
		assertEquals(7, i.getIntExtra(com.professor.zerion.android
				.conversation.ConversationActivity.CONTACT_ID, -1));
	}

	@Test
	public void theSentRowTurnsIntoContactAddedWithOpenChat() {
		out.add(outgoing(1, "Sam", OutgoingRequest.STATE_DELIVERED, NOW - 1,
				NOW, null));
		open(RequestsController.TAB_SENT);
		out.clear();
		out.add(outgoing(1, "Sam", OutgoingRequest.STATE_ADDED, NOW - 1,
				NOW, null));
		controller.onContactAdded(id(1), new ContactId(5));
		db.runAll();
		layout();
		assertEquals(1, rows());
		String t = text(row(0));
		assertTrue(t, t.contains("Sam"));
		assertTrue(t, t.contains("Contact added"));
		assertEquals("Open chat", actionText(row(0)));
		row(0).findViewById(R.id.requestAction).performClick();
		assertEquals(5, shadowOf(host).getNextStartedActivity().getIntExtra(
				com.professor.zerion.android.conversation.ConversationActivity
						.CONTACT_ID, -1));
	}

	@Test
	public void aContactAddedWhileAnUndoIsShownKeepsTheUndo() {
		in.add(incoming(1, "Alex", null, IncomingRequest.STATE_OPENED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		in.add(incoming(2, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW - DAY, NOW - DAY, null));
		open(RequestsController.TAB_RECEIVED);
		row(0).performClick();
		RequestFixtures.latestAlert().getButton(AlertDialog.BUTTON_NEGATIVE)
				.performClick();
		idle();
		controller.onContactAdded(id(2), new ContactId(3));
		db.runAll();
		findSnackbarAction();
	}

	@Test
	public void tryAgainShowsConnectingAgainFromTheNewAttempt() {
		PendingContact p = pendingContact(9, "Alex");
		pending.add(new Pair<>(p, PendingContactState.WAITING_FOR_CONNECTION));
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW - DAY, NOW - DAY,
				p.getId()));
		clock = NOW + 30_000;
		open(RequestsController.TAB_RECEIVED);
		String t = text(row(0));
		assertTrue(t, t.contains("Connecting…"));
		assertTrue(progressShown(row(0)));
	}

	@Test
	public void noRefreshRunsAfterTheScreenStops() throws Exception {
		PendingContact p = pendingContact(9, "Alex");
		pending.add(new Pair<>(p, PendingContactState.WAITING_FOR_CONNECTION));
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, p.getId()));
		clock = NOW + 30_000;
		open(RequestsController.TAB_RECEIVED);
		controller.reload();
		controller.flushDeclines();
		controller.onStop();
		db.runAll();
		verify(requests, times(2)).getIncomingRequests();
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
				3 * RequestRow.CONNECTING_SHOWN_MS));
		db.runAll();
		verify(requests, times(2)).getIncomingRequests();
	}

	@Test
	public void destroyForgetsTheAddedRows() {
		in.add(incoming(1, null, null, IncomingRequest.STATE_ACCEPTED,
				ContactLink.KIND_ZERO_ID, null, NOW, NOW, null));
		open(RequestsController.TAB_RECEIVED);
		in.clear();
		controller.onContactAdded(id(1), new ContactId(4));
		db.runAll();
		layout();
		assertEquals(1, rows());
		controller.destroy();
		controller.reload();
		db.runAll();
		layout();
		assertEquals(0, rows());
	}
}
