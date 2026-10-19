package com.professor.zerion.android.grouptr;

import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.homelist.HomeListProbes;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPendingInvite;
import org.zerionproject.app.api.grouptr.GroupTrPostSummary;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.homelist.HomeListProbes.rows;
import static com.professor.zerion.android.homelist.HomeListProbes.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getLocalAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupListRowsTest {

	static {
		TestAndroidKeyStore.register();
	}

	static final List<Runnable> io = new ArrayList<>();
	static GroupTrManager manager;
	static IdentityManager identity;
	static ContactManager contacts;

	public static class ProbeList extends GroupTrListFragment {

		@Override
		public void injectFragment(ActivityComponent component) {
			groupTrManager = manager;
			eventBus = mock(EventBus.class);
			ioExecutor = io::add;
			identityManager = identity;
			contactManager = contacts;
		}
	}

	private final byte[] groupId = getRandomId();
	private final byte[] samKey = key((byte) 5);
	private final LocalAuthor me = getLocalAuthor();
	private final Author dana = getAuthor();
	private final long now = System.currentTimeMillis();

	@Before
	public void setUp() throws Exception {
		io.clear();
		manager = mock(GroupTrManager.class);
		identity = mock(IdentityManager.class);
		contacts = mock(ContactManager.class);
		when(identity.getLocalAuthor()).thenReturn(me);
		when(contacts.getContacts()).thenReturn(Collections.singletonList(
				new Contact(new ContactId(7), dana, new AuthorId(getRandomId()),
						"Dana", getAgreementPublicKey(), true)));
		when(manager.getPendingInvites()).thenReturn(
				Collections.<GroupTrPendingInvite>emptyList());
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}

	private GroupTrState group(String name, boolean dissolved) {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(me.getPublicKey().getEncoded(), "Me",
				0L, 0L, MemberRole.CREATOR));
		members.add(new GroupTrMember(samKey, "Sam", 0L, 0L,
				MemberRole.MEMBER));
		members.add(new GroupTrMember(dana.getPublicKey().getEncoded(),
				"D.", 0L, 0L, MemberRole.MEMBER));
		return new GroupTrState(groupId, name, new byte[32],
				me.getPublicKey().getEncoded(), "Me", 0L, 0L, dissolved,
				members);
	}

	private void groups(GroupTrState... states) throws Exception {
		when(manager.getGroups()).thenReturn(Arrays.asList(states));
	}

	private void latest(byte[] sender, byte[] body) throws Exception {
		latest(sender, "chosen", body);
	}

	private void latest(byte[] sender, String chosenName, byte[] body)
			throws Exception {
		GroupTrPostSummary.Kind kind;
		switch (GroupTrBody.kindOf(body)) {
			case VOICE:
				kind = GroupTrPostSummary.Kind.VOICE;
				break;
			case IMAGE:
				kind = GroupTrPostSummary.Kind.IMAGE;
				break;
			case VIDEO:
				kind = GroupTrPostSummary.Kind.VIDEO;
				break;
			default:
				kind = GroupTrPostSummary.Kind.TEXT;
				break;
		}
		String text = kind == GroupTrPostSummary.Kind.TEXT
				? GroupTrBody.parse(body).text : "";
		summary(new GroupTrPostSummary(sender, chosenName, kind, text, now,
				Long.MAX_VALUE));
	}

	private void summary(GroupTrPostSummary s) throws Exception {
		when(manager.getLatestPostSummary(any())).thenReturn(s);
	}

	private ProbeList open() {
		ProbeList list = new ProbeList();
		HomeListProbes.host(list, io);
		return list;
	}

	private View onlyRow(ProbeList list) {
		List<View> rows = rows(list);
		assertEquals(1, rows.size());
		return rows.get(0);
	}

	@Test
	public void aGroupRowShowsTheLatestPostAndItsSender() throws Exception {
		groups(group("Volunteers", false));
		latest(samKey, GroupTrBody.encodeText("Two more boxes arrived"));
		View row = onlyRow(open());
		assertEquals("Volunteers", text(row, R.id.homeRowName));
		assertTrue(!text(row, R.id.homeRowTime).isEmpty());
		assertTrue(!text(row, R.id.homeRowName).contains(
				text(row, R.id.homeRowTime)));
		assertEquals("Sam: Two more boxes arrived",
				text(row, R.id.homeRowPreview));
	}

	@Test
	public void aPostFromSomeoneWithoutANameShowsJustTheText()
			throws Exception {
		groups(group("Volunteers", false));
		latest(key((byte) 9), "", GroupTrBody.encodeText("Boxes are here"));
		String preview = text(onlyRow(open()), R.id.homeRowPreview);
		assertEquals("Boxes are here", preview);
		assertTrue(preview, !preview.contains("null"));
		assertTrue(preview, !preview.contains("?"));
	}

	@Test
	public void ownPostsAndContactsUseTheNamesYouKnow() throws Exception {
		groups(group("Volunteers", false));
		latest(me.getPublicKey().getEncoded(), GroupTrBody.encodeText("On my way"));
		assertEquals("You: On my way",
				text(onlyRow(open()), R.id.homeRowPreview));
		latest(dana.getPublicKey().getEncoded(),
				GroupTrBody.encodeText("Thanks"));
		assertEquals("Dana: Thanks",
				text(onlyRow(open()), R.id.homeRowPreview));
	}

	@Test
	public void aContactPostingIncognitoStaysIncognito() throws Exception {
		groups(group("Volunteers", false));
		latest(dana.getPublicKey().getEncoded(), "Amber Falcon 27",
				GroupTrBody.encodeText("Thanks"));
		String preview = text(onlyRow(open()), R.id.homeRowPreview);
		assertEquals("\"Amber Falcon 27\" (incognito): Thanks", preview);
		assertTrue(preview, !preview.contains("Dana"));
		assertTrue(preview, !preview.contains("D."));
	}

	@Test
	public void talkBackHearsAnIncognitoRowLikeAnyOther() throws Exception {
		groups(group("Incog Test", false));
		latest(dana.getPublicKey().getEncoded(), "Silver Ibis 41",
				GroupTrBody.encodeText("grpreply2025"));
		when(manager.getUnreadCount(any())).thenReturn(2);
		View row = onlyRow(open());
		String spoken = String.valueOf(row.getContentDescription());
		assertEquals("Incog Test, 2 unread, " + text(row, R.id.homeRowTime)
				+ ", \"Silver Ibis 41\" (incognito): grpreply2025", spoken);
		assertEquals(spoken, String.valueOf(
				row.createAccessibilityNodeInfo().getContentDescription()));
	}

	@Test
	public void voicePostsAreNamedNotShownAsBytes() throws Exception {
		groups(group("Volunteers", false));
		latest(samKey, GroupTrBody.encodeVoice(new byte[64], 3000L));
		assertEquals("Sam: Voice message",
				text(onlyRow(open()), R.id.homeRowPreview));
	}

	@Test
	public void picturesAndVideosAreNamed() throws Exception {
		groups(group("Volunteers", false));
		latest(samKey, GroupTrBody.encodeImage(new byte[64], "image/jpeg"));
		assertEquals("Sam: Image", text(onlyRow(open()), R.id.homeRowPreview));
		latest(samKey, GroupTrBody.encodeVideo(new byte[64], "video/mp4",
				2_000L));
		assertEquals("Sam: Video", text(onlyRow(open()), R.id.homeRowPreview));
		summary(new GroupTrPostSummary(samKey, "Sam",
				GroupTrPostSummary.Kind.MEDIA, "", now, Long.MAX_VALUE));
		assertEquals("Sam: Attachment",
				text(onlyRow(open()), R.id.homeRowPreview));
	}

	@Test
	public void theListNeverLoadsAGroupsHistory() throws Exception {
		groups(group("Volunteers", false), group("Club", false));
		latest(samKey, GroupTrBody.encodeText("Two more boxes arrived"));
		open();
		verify(manager, never()).getRecentPosts(any());
		verify(manager, times(2)).getLatestPostSummary(any());
	}

	@Test
	public void aClosedGroupSaysTheOwnerClosedIt() throws Exception {
		groups(group("Book club", true));
		latest(samKey, GroupTrBody.encodeText("Bye"));
		assertEquals("Closed by the group owner",
				text(onlyRow(open()), R.id.homeRowPreview));
	}

	@Test
	public void aGroupWithoutPostsCountsItsMembers() throws Exception {
		groups(group("Family", false));
		View row = onlyRow(open());
		assertEquals("3 members", text(row, R.id.homeRowPreview));
		assertEquals(View.GONE, row.findViewById(R.id.homeRowTime)
				.getVisibility());
	}

	@Test
	public void theUnreadBadgeStopsAt99() throws Exception {
		groups(group("Volunteers", false));
		when(manager.getUnreadCount(any())).thenReturn(250);
		View row = onlyRow(open());
		assertEquals("99+", text(row, R.id.homeRowUnread));
		assertTrue(String.valueOf(row.getContentDescription())
				.contains("250 unread"));
	}

	@Test
	public void tappingARowOpensTheGroup() throws Exception {
		groups(group("Volunteers", false));
		ProbeList list = open();
		onlyRow(list).performClick();
		Intent next = shadowOf(list.requireActivity())
				.getNextStartedActivity();
		assertNotNull(next);
		assertEquals(GroupTrConversationActivity.class.getName(),
				next.getComponent().getClassName());
	}

	@Test
	public void holdingARowStillOffersToRemoveTheGroup() throws Exception {
		groups(group("Volunteers", false));
		ProbeList list = open();
		assertTrue(onlyRow(list).performLongClick());
		HomeListProbes.run(io);
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull("no remove dialog", d);
		assertEquals(list.getString(R.string.grouptr_remove_local_title,
				"Volunteers"), HomeListProbes.dialogTitle(d));
	}

	@Test
	public void invitationsCanStillBeAnswered() throws Exception {
		groups();
		byte[] invited = getRandomId();
		when(manager.getPendingInvites()).thenReturn(Collections.singletonList(
				new GroupTrPendingInvite(invited, "Book club", "Sam", now)));
		ProbeList list = open();
		View invite = HomeListProbes.child(list, 0);
		assertEquals(R.id.homeInvite, invite.getId());
		assertEquals("Book club, invited by Sam",
				text(invite, R.id.homeInviteTitle));
		assertNull("an invitation is something to show",
				HomeListProbes.empty(list));

		invite.findViewById(R.id.homeInviteDecline).performClick();
		HomeListProbes.run(io);
		verify(manager).declineInvite(invited);

		ShadowDialog.reset();
		HomeListProbes.child(list, 0).findViewById(R.id.homeInviteAccept)
				.performClick();
		HomeListProbes.run(io);
		verify(manager).canAcceptIncognito(any(), anyBoolean());
		assertNotNull("no join dialog", ShadowDialog.getLatestDialog());
	}

	@Test
	public void twoInvitationsToOneGroupBothShow() throws Exception {
		groups();
		byte[] invited = getRandomId();
		when(manager.getPendingInvites()).thenReturn(Arrays.asList(
				new GroupTrPendingInvite(invited, "Book club", "Sam", now),
				new GroupTrPendingInvite(invited, "Book club", "Dana", now)));
		ProbeList list = open();
		RecyclerView view =
				HomeListProbes.list(list);
		assertEquals(2, view.getChildCount());
		assertEquals("Book club, invited by Sam",
				text(view.getChildAt(0), R.id.homeInviteTitle));
		assertEquals("Book club, invited by Dana",
				text(view.getChildAt(1), R.id.homeInviteTitle));
		RecyclerView.Adapter<?> adapter =
				view.getAdapter();
		assertNotNull(adapter);
		assertTrue(adapter.getItemId(0) != adapter.getItemId(1));
	}

	@Test
	public void aFailedLoadSaysSoAndCanBeTriedAgain() throws Exception {
		when(manager.getGroups()).thenThrow(new DbException());
		ProbeList list = open();
		View state = HomeListProbes.empty(list);
		assertNotNull(state);
		assertEquals("This list could not be loaded.",
				text(state, R.id.homeEmptyTitle));
		assertTrue(!text(state, R.id.homeEmptyTitle).equals(
				list.getString(R.string.groups_list_empty)));
		View retry = state.findViewById(R.id.homeEmptyRetry);
		assertEquals(View.VISIBLE, retry.getVisibility());
		doReturn(Collections.singletonList(group("Family", false)))
				.when(manager).getGroups();
		retry.performClick();
		HomeListProbes.run(io);
		assertEquals("Family", text(onlyRow(list), R.id.homeRowName));
		assertNull(HomeListProbes.empty(list));
	}

	@Test
	public void oneGroupThatFailsToLoadDoesNotHideTheOthers()
			throws Exception {
		byte[] brokenId = getRandomId();
		GroupTrState broken = new GroupTrState(brokenId, "Broken",
				new byte[32], me.getPublicKey().getEncoded(), "Me", 0L, 0L,
				false, group("x", false).getMembers());
		groups(group("Volunteers", false), broken);
		when(manager.getLatestPostSummary(any())).thenAnswer(inv ->
				Arrays.equals((byte[]) inv.getArgument(0), brokenId)
						? throwing() : new GroupTrPostSummary(samKey, "Sam",
						GroupTrPostSummary.Kind.TEXT, "hello", now,
						Long.MAX_VALUE));
		when(manager.isLocalScreenshotBlocked(any())).thenAnswer(inv -> {
			if (Arrays.equals((byte[]) inv.getArgument(0), brokenId)) {
				throw new DbException();
			}
			return false;
		});
		ProbeList list = open();
		List<View> rows = rows(list);
		assertEquals(2, rows.size());
		assertNull("no error state for one broken group",
				HomeListProbes.empty(list));
		for (View row : rows) {
			String name = text(row, R.id.homeRowName);
			String preview = text(row, R.id.homeRowPreview);
			if (name.equals("Broken")) assertEquals("3 members", preview);
			else assertEquals("Sam: hello", preview);
		}
	}

	private static GroupTrPostSummary throwing() throws DbException {
		throw new DbException();
	}

	@Test
	public void anEmptyListShowsTheSharedEmptyState() throws Exception {
		groups();
		ProbeList list = open();
		View empty = HomeListProbes.empty(list);
		assertEquals(View.VISIBLE, empty.getVisibility());
		assertEquals(list.getString(R.string.groups_list_empty),
				text(empty, R.id.homeEmptyTitle));
		assertEquals(list.getString(R.string.groups_list_empty_action),
				text(empty, R.id.homeEmptyBody));
	}

	@Test
	public void aPostDeletedForEveryoneLeavesThePreview() throws Exception {
		groups(group("Volunteers", false));
		latest(samKey, GroupTrBody.encodeText("Oops, wrong group"));
		ProbeList list = open();
		assertEquals("Sam: Oops, wrong group",
				text(onlyRow(list), R.id.homeRowPreview));
		summary(null);
		list.eventOccurred(new GroupTrPostsRemovedEvent(groupId,
				Collections.singletonList(new byte[32]), 0));
		HomeListProbes.afterDebounce(io);
		assertEquals("3 members", text(onlyRow(list), R.id.homeRowPreview));
	}

	@Test
	public void aDisappearingPostLeavesThePreviewWhenItExpires()
			throws Exception {
		groups(group("Volunteers", false));
		long wall = System.currentTimeMillis();
		summary(new GroupTrPostSummary(samKey, "Sam",
				GroupTrPostSummary.Kind.TEXT, "Gone soon", wall, wall + 5_000L));
		ProbeList list = open();
		assertEquals("Sam: Gone soon", text(onlyRow(list), R.id.homeRowPreview));
		summary(null);
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7));
		HomeListProbes.run(io);
		assertEquals("3 members", text(onlyRow(list), R.id.homeRowPreview));
	}

	@Test
	public void aGroupThatBlocksScreenshotsShowsNoContentOnTheList()
			throws Exception {
		groups(group("Volunteers", false));
		latest(samKey, GroupTrBody.encodeText("the private plan"));
		when(manager.isLocalScreenshotBlocked(any())).thenReturn(true);
		View row = onlyRow(open());
		String preview = text(row, R.id.homeRowPreview);
		assertEquals("3 members", preview);
		String spoken = String.valueOf(row.getContentDescription());
		assertTrue(spoken, !spoken.contains("private plan"));
		assertTrue(spoken, !spoken.contains("Sam"));
		assertEquals("Volunteers, " + text(row, R.id.homeRowTime)
				+ ", 3 members", spoken);
	}

	@Test
	public void anOlderLoadFinishingLastDoesNotBringBackAnInvitation()
			throws Exception {
		groups();
		byte[] invited = getRandomId();
		AtomicBoolean stale = new AtomicBoolean(true);
		when(manager.getPendingInvites()).thenAnswer(inv -> stale.get()
				? Collections.singletonList(new GroupTrPendingInvite(invited,
						"Book club", "Sam", now))
				: Collections.<GroupTrPendingInvite>emptyList());
		ProbeList list = open();
		assertEquals(R.id.homeInvite, HomeListProbes.child(list, 0).getId());
		GroupTrLocalStateChangedEvent changed =
				new GroupTrLocalStateChangedEvent(groupId,
						GroupTrLocalStateChangedEvent.Kind.UPDATED);
		list.eventOccurred(changed);
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
		List<Runnable> older = new ArrayList<>(io);
		io.clear();
		list.eventOccurred(changed);
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
		List<Runnable> newer = new ArrayList<>(io);
		io.clear();
		Thread slow = new Thread(() -> {
			for (Runnable r : older) r.run();
		});
		slow.start();
		slow.join();
		stale.set(false);
		for (Runnable r : newer) r.run();
		HomeListProbes.run(io);
		RecyclerView view = HomeListProbes.list(list);
		for (int i = 0; i < view.getChildCount(); i++) {
			assertTrue("the declined invitation came back",
					view.getChildAt(i).getId() != R.id.homeInvite);
		}
		assertEquals(View.VISIBLE, HomeListProbes.empty(list).getVisibility());
	}

	@Test
	public void aReloadUpdatesRowsInPlace() throws Exception {
		groups(group("Volunteers", false));
		ProbeList list = open();
		View before = onlyRow(list);
		when(manager.getUnreadCount(any())).thenReturn(4);
		list.eventOccurred(new GroupTrLocalStateChangedEvent(groupId,
				GroupTrLocalStateChangedEvent.Kind.UPDATED));
		HomeListProbes.afterDebounce(io);
		View after = onlyRow(list);
		assertSame("the row was rebuilt", before, after);
		assertEquals("4", text(after, R.id.homeRowUnread));
	}
}
