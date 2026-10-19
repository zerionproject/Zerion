package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.message.TestPendingSends;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.SnackbarProbe;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrSendResult;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.util.StringUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupSendStateTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {

		static GroupTrManager manager;
		static IdentityManager identity;
		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = identity;
			ioExecutor = io::add;
		}
	}

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] firstId = key((byte) 12);
	private final byte[] secondId = key((byte) 13);
	private byte[] groupId;
	private GroupTrManager manager;
	private GroupTrPost theirs;
	private ActivityController<ProbeConversation> controller;
	private ProbeConversation activity;

	@Before
	public void setUp() throws Exception {
		groupId = StringUtils.fromHexString(GROUP_HEX);
		ProbeConversation.io.clear();
		manager = mock(GroupTrManager.class);
		IdentityManager identity = mock(IdentityManager.class);
		when(identity.getLocalAuthor()).thenReturn(new LocalAuthor(
				new AuthorId(key((byte) 9)), 1, "Local",
				new SignaturePublicKey(localKey),
				new SignaturePrivateKey(new byte[32])));
		ProbeConversation.identity = identity;
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Creator", 0L, 0L,
				MemberRole.CREATOR));
		members.add(new GroupTrMember(localKey, "Local", 0L, 0L));
		when(manager.getGroup(any())).thenReturn(new GroupTrState(groupId,
				"Group", new byte[32], creatorKey, "Creator", 0L, 1L, false,
				members));
		when(manager.getMembersOutOfReach(any()))
				.thenReturn(Collections.emptyList());
		theirs = post(creatorKey, GroupTrBody.encodeText("hi all"), 1_000L,
				key((byte) 11));
		when(manager.getRecentPosts(any()))
				.thenReturn(Collections.singletonList(theirs));
		ProbeConversation.manager = manager;
		activity = open();
	}

	private static GroupTrSendResult sent(byte[] id) {
		return new GroupTrSendResult(id, 1, 1, 0, 0);
	}

	private GroupTrPost stored(String text, byte[] id) {
		return post(localKey, text.getBytes(StandardCharsets.UTF_8), 2_000L,
				id);
	}

	@Test
	public void aPostShowsAtOnceWhileItIsBeingSent() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenAnswer(inv -> {
					entered.countDown();
					assertTrue(release.await(10, TimeUnit.SECONDS));
					return sent(firstId);
				});

		type("hello");
		send();

		assertEquals("", input().getText().toString());
		assertEquals("the post is on screen before it is sent", 2,
				adapter().getItemCount());
		assertEquals("hello", text(1, R.id.postText));
		assertTrue(meta(1).endsWith(state(R.string.message_state_sending)));
		verify(manager, never()).sendGroupPostReporting(any(), any(),
				anyLong());

		Thread fanOut = new Thread(ProbeConversation.io.remove(0));
		fanOut.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		idle();
		assertTrue(meta(1).endsWith(state(R.string.message_state_sending)));

		when(manager.getRecentPosts(any())).thenReturn(Arrays.asList(theirs,
				stored("hello", firstId)));
		release.countDown();
		fanOut.join(10_000);
		runIo();

		assertEquals("the stored post replaces the sending row", 2,
				adapter().getItemCount());
		assertFalse(meta(1).contains("·"));
	}

	@Test
	public void twoIdenticalPostsInARowEachShowUntilTheirOwnPostArrives()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(sent(firstId), sent(secondId));
		type("ok");
		send();
		ProbeConversation.io.remove(0).run();
		type("ok");
		send();
		when(manager.getRecentPosts(any())).thenReturn(Arrays.asList(theirs,
				stored("ok", firstId)));
		Runnable reload = ProbeConversation.io.remove(1);
		reload.run();
		idle();

		assertEquals("the second post still shows while it is sent", 3,
				adapter().getItemCount());
		assertTrue(meta(2).endsWith(state(R.string.message_state_sending)));

		when(manager.getRecentPosts(any())).thenReturn(Arrays.asList(theirs,
				stored("ok", firstId), stored("ok", secondId)));
		runIo();
		assertEquals(3, adapter().getItemCount());
		assertFalse(meta(2).contains("·"));
	}

	@Test
	public void aFailedPostKeepsItsTextAndOffersRetry() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());

		type("hello");
		send();
		runIo();

		assertEquals(2, adapter().getItemCount());
		assertEquals("hello", text(1, R.id.postText));
		assertTrue(meta(1).endsWith(state(R.string.message_state_not_sent)));
		View row = holder(1).itemView;
		assertNotNull("the bubble is outlined",
				row.findViewById(R.id.bubble).getForeground());
		for (int id : new int[] {R.id.messageEditButton,
				R.id.messageRetryButton, R.id.messageDeleteButton}) {
			assertEquals(View.VISIBLE, row.findViewById(id).getVisibility());
		}
		assertEquals(activity.getResources().getQuantityString(
				R.plurals.message_state_could_not_send, 1, 1),
				SnackbarProbe.text(activity));
		TextView action = SnackbarProbe.action(activity);
		assertNotNull(action);
		assertEquals(activity.getString(R.string.message_state_retry),
				action.getText().toString());
	}

	@Test
	public void retryingSendsThePostExactlyOnceMore() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException()).thenReturn(sent(firstId));
		type("hello");
		send();
		runIo();

		View retry = holder(1).itemView.findViewById(R.id.messageRetryButton);
		retry.performClick();
		retry.performClick();
		idle();
		assertTrue(meta(1).endsWith(state(R.string.message_state_sending)));
		when(manager.getRecentPosts(any())).thenReturn(Arrays.asList(theirs,
				stored("hello", firstId)));
		runIo();

		verify(manager, times(2)).sendGroupPostReporting(eq(groupId),
				eq("hello".getBytes(StandardCharsets.UTF_8)), eq(0L));
		assertEquals(2, adapter().getItemCount());
	}

	@Test
	public void theSnackbarRetryResendsOnce() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException()).thenReturn(sent(firstId));
		type("hello");
		send();
		runIo();

		TextView action = SnackbarProbe.action(activity);
		assertNotNull(action);
		action.performClick();
		runIo();

		verify(manager, times(2)).sendGroupPostReporting(any(), any(),
				anyLong());
	}

	@Test
	public void aPostThatReachedSomeMembersIsSentWithANoticeAndNoRetry()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(firstId, 4, 3, 1, 0));
		type("hello");
		send();
		when(manager.getRecentPosts(any())).thenReturn(Arrays.asList(theirs,
				stored("hello", firstId)));
		runIo();

		assertEquals(activity.getResources().getQuantityString(
				R.plurals.grouptr_ready_for_some, 4, 2, 4),
				SnackbarProbe.text(activity));
		assertEquals("every other member counts, not only contacts",
				"Ready to go to 2 of 4 members.",
				SnackbarProbe.text(activity));
		assertNull("nothing to retry", SnackbarProbe.action(activity));
		assertEquals(2, adapter().getItemCount());
		assertFalse(meta(1).contains("·"));
	}

	@Test
	public void aPostTheGroupNoLongerTakesGoesBackIntoTheComposer()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_A_MEMBER));
		type("hello");
		send();
		runIo();

		assertEquals("hello", input().getText().toString());
		assertEquals("no row is left behind", 1, adapter().getItemCount());
		assertEquals(activity.getString(R.string.grouptr_error_send),
				SnackbarProbe.text(activity));
		verify(manager, times(1)).sendGroupPostReporting(any(), any(),
				anyLong());
	}

	@Test
	public void editPutsAFailedPostBackOnlyIntoAnEmptyComposer()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("hello");
		send();
		runIo();

		type("draft");
		holder(1).itemView.findViewById(R.id.messageEditButton).performClick();
		idle();
		assertEquals("draft", input().getText().toString());
		assertEquals("the post is kept", 2, adapter().getItemCount());

		type("");
		holder(1).itemView.findViewById(R.id.messageEditButton).performClick();
		idle();
		assertEquals("hello", input().getText().toString());
		assertEquals(1, adapter().getItemCount());
	}

	@Test
	public void deletingTwoPostsInARowLetsUndoBringBothBack()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("hello");
		send();
		runIo();
		type("again");
		send();
		runIo();
		assertEquals(3, adapter().getItemCount());

		holder(1).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		idle();
		holder(1).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		idle();
		assertEquals(1, adapter().getItemCount());
		TextView undo = SnackbarProbe.action(activity);
		assertNotNull(undo);
		undo.performClick();
		idle();

		assertEquals("both deleted posts come back", 3,
				adapter().getItemCount());
		assertEquals("hello", text(1, R.id.postText));
		assertEquals("again", text(2, R.id.postText));
	}

	@Test
	public void aRefusedPostDoesNotTakeBackAReplyBeingWritten()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_A_MEMBER));
		type("hello");
		send();
		startReplyTo(0);
		View preview = activity.findViewById(R.id.groupReplyPreview);
		assertEquals(View.VISIBLE, preview.getVisibility());

		runIo();

		assertEquals("the reply being written is kept", View.VISIBLE,
				preview.getVisibility());
		assertEquals("", input().getText().toString());
		assertEquals("the refused post stays as a row", 2,
				adapter().getItemCount());
	}

	@Test
	public void cancellingTheReplyTakesTheRefusedPostBack() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_A_MEMBER));
		type("hello");
		send();
		startReplyTo(0);
		runIo();
		assertEquals(2, adapter().getItemCount());

		activity.findViewById(R.id.cancel_reply).performClick();
		idle();

		assertEquals("the refused post is back in the composer", "hello",
				input().getText().toString());
		assertEquals("no row is left behind", 1, adapter().getItemCount());
	}

	@Test
	public void deletingAPostThatWasNotSentCanBeUndone() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("hello");
		send();
		runIo();

		holder(1).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		idle();
		assertEquals(1, adapter().getItemCount());
		assertEquals(activity.getString(R.string.message_state_deleted),
				SnackbarProbe.text(activity));
		TextView undo = SnackbarProbe.action(activity);
		assertNotNull(undo);
		assertEquals(activity.getString(R.string.message_state_undo),
				undo.getText().toString());
		undo.performClick();
		idle();
		assertEquals(2, adapter().getItemCount());
		assertEquals("hello", text(1, R.id.postText));
		verify(manager, times(1)).sendGroupPostReporting(any(), any(),
				anyLong());
	}

	@Test
	public void aPickedImageIsStillSentWhenTheScreenHasClosed()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(sent(firstId));
		byte[] body = GroupTrBody.encodeImage(new byte[64], "image/jpeg");
		activity.finish();
		Method m = GroupTrConversationActivity.class.getDeclaredMethod(
				"sendBodyAsync", byte[].class);
		m.setAccessible(true);
		m.invoke(activity, (Object) body);
		runIo();

		verify(manager).sendGroupPostReporting(eq(groupId), eq(body),
				eq(0L));
	}

	@Test
	public void aNoticeForAPostStoredWhileAwayShowsOnTheNextVisit()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(firstId, 2, 1, 0, 1));
		type("hello");
		send();
		controller.pause().stop();
		ProbeConversation.io.remove(0).run();
		idle();
		assertTrue("nothing kept while nobody looks", PendingSends.get()
				.entries(PendingSends.groupScope(groupId)).isEmpty());

		controller.restart().resume();
		runIo();

		assertEquals(activity.getResources().getQuantityString(
				R.plurals.grouptr_ready_for_some, 2, 1, 2) + " "
				+ activity.getResources().getQuantityString(
				R.plurals.grouptr_post_not_sent_older, 1, 1),
				SnackbarProbe.text(activity));
	}

	@Test
	public void aPostSomeMembersCannotGetSaysHowManyItIsReadyFor()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(firstId, 3, 2, 0, 0));
		type("hello");
		send();
		runIo();

		assertEquals("Ready to go to 2 of 3 members.",
				SnackbarProbe.text(activity));
	}

	@Test
	public void removingAnotherGroupLeavesThisScreensUndo() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("hello");
		send();
		runIo();
		holder(1).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		idle();
		TextView undo = SnackbarProbe.action(activity);
		assertNotNull(undo);

		PendingSends.get().removeGroup(key((byte) 5));
		idle();

		java.lang.reflect.Field bar = GroupTrConversationActivity.class
				.getDeclaredField("undoBar");
		bar.setAccessible(true);
		assertNotNull("the undo is still offered", bar.get(activity));
		undo.performClick();
		idle();
		assertEquals(2, adapter().getItemCount());
		assertEquals("hello", text(1, R.id.postText));
	}

	@Test
	public void aFinishedPostWaitsRatherThanClaimingItWasSent()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(sent(firstId));
		type("hello");
		send();
		runIo();

		assertEquals(2, adapter().getItemCount());
		assertTrue("copies are only on their way",
				meta(1).endsWith(state(R.string.message_state_waiting)));
	}

	@Test
	public void aPostNoMemberCanGetStaysUnsentWithItsActions()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(firstId, 2, 0, 0, 0));
		type("hello");
		send();
		runIo();

		assertEquals("No member could get this post.",
				SnackbarProbe.text(activity));
		assertEquals(2, adapter().getItemCount());
		assertEquals("hello", text(1, R.id.postText));
		assertTrue("the post says it was not sent",
				meta(1).endsWith(state(R.string.message_state_not_sent)));
		View row = holder(1).itemView;
		for (int id : new int[] {R.id.messageEditButton,
				R.id.messageRetryButton, R.id.messageDeleteButton}) {
			assertEquals(View.VISIBLE, row.findViewById(id).getVisibility());
		}
	}

	@Test
	public void aPostEveryMemberIsTooOldForSaysWhyNobodyGotIt()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(firstId, 2, 0, 0, 2));
		type("hello");
		send();
		runIo();

		assertEquals(activity.getString(R.string.grouptr_post_reached_nobody)
				+ " " + activity.getResources().getQuantityString(
				R.plurals.grouptr_post_not_sent_older, 2, 2),
				SnackbarProbe.text(activity));
		assertTrue(meta(1).endsWith(state(R.string.message_state_not_sent)));
	}

	@Test
	public void whyNobodyGotAPostIsToldWhenTheScreenComesBack()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(firstId, 2, 0, 0, 2));
		type("hello");
		send();
		controller.pause().stop();
		ProbeConversation.io.remove(0).run();
		idle();

		controller.restart().resume();
		runIo();

		assertEquals(activity.getString(R.string.grouptr_post_reached_nobody)
				+ " " + activity.getResources().getQuantityString(
				R.plurals.grouptr_post_not_sent_older, 2, 2),
				SnackbarProbe.text(activity));
		assertTrue(meta(1).endsWith(state(R.string.message_state_not_sent)));
		assertEquals(View.VISIBLE, holder(1).itemView.findViewById(
				R.id.messageRetryButton).getVisibility());
	}

	@Test
	public void aPostRefusedWhileAwayComesBackWithItsReason()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_A_MEMBER));
		type("hello");
		send();
		controller.pause().stop();
		ProbeConversation.io.remove(0).run();
		idle();

		controller.restart().resume();
		runIo();

		assertEquals("hello", input().getText().toString());
		assertEquals(1, adapter().getItemCount());
		assertEquals("the reason is shown once", 1, Collections.frequency(
				SnackbarProbe.texts(activity),
				activity.getString(R.string.grouptr_error_send)));
	}

	@Test
	public void aPostInAGroupOfOneIsHandedOverWithoutANotice()
			throws Exception {
		when(manager.getGroup(any())).thenReturn(new GroupTrState(groupId,
				"Group", new byte[32], localKey, "Local", 0L, 1L, false,
				Collections.singletonList(new GroupTrMember(localKey, "Local",
						0L, 0L, MemberRole.CREATOR))));
		reload();
		runIo();
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(firstId, 0, 0, 0, 0));
		type("hello");
		send();
		runIo();

		assertEquals(2, adapter().getItemCount());
		assertTrue("it is handed over", meta(1).endsWith(
				state(R.string.message_state_waiting)));
		assertEquals("nothing to retry, edit or delete", View.GONE,
				holder(1).itemView.findViewById(
						R.id.messageNotSentActions).getVisibility());
		assertNull("nothing to tell", SnackbarProbe.text(activity));
	}

	private void assertARefusedActionCanBeTappedAgain(int button)
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("hello");
		send();
		runIo();
		input().setText("");
		View tapped = holder(1).itemView.findViewById(button);
		PendingSends.Entry e = PendingSends.get().entries(
				PendingSends.groupScope(groupId)).get(0);
		List<Runnable> running = new ArrayList<>();
		assertTrue(PendingSends.get().retry(e, running::add));
		TestPendingSends.setClock(() -> {
			if (!running.isEmpty()) running.remove(0).run();
			return System.currentTimeMillis();
		});

		tapped.performClick();
		TestPendingSends.setClock(System::currentTimeMillis);
		idle();

		assertTrue("the running send ended just after the refusal",
				running.isEmpty());
		assertTrue(meta(1).endsWith(state(R.string.message_state_not_sent)));
		View now = holder(1).itemView.findViewById(button);
		assertEquals(View.VISIBLE, now.getVisibility());
		assertTrue("the refused action is not left greyed out",
				now.isEnabled());
		assertEquals("", input().getText().toString());
		assertEquals(2, adapter().getItemCount());
	}

	@Test
	public void anEditRefusedWhileTheSendRunsCanBeTappedAgain()
			throws Exception {
		assertARefusedActionCanBeTappedAgain(R.id.messageEditButton);
	}

	@Test
	public void aDeleteRefusedWhileTheSendRunsCanBeTappedAgain()
			throws Exception {
		assertARefusedActionCanBeTappedAgain(R.id.messageDeleteButton);
	}

	@Test
	public void aRetryRefusedWhileTheSendRunsCanBeTappedAgain()
			throws Exception {
		assertARefusedActionCanBeTappedAgain(R.id.messageRetryButton);
	}

	@Test
	public void editingAPlainPostClearsAnOpenReplyQuote() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("hello");
		send();
		runIo();
		startReplyTo(0);
		View preview = activity.findViewById(R.id.groupReplyPreview);
		assertEquals(View.VISIBLE, preview.getVisibility());

		holder(1).itemView.findViewById(R.id.messageEditButton).performClick();
		idle();

		assertEquals("hello", input().getText().toString());
		assertEquals("the post was not a reply", View.GONE,
				preview.getVisibility());
	}

	@Test
	public void editingAReplyWhoseOriginalIsGoneSaysSo() throws Exception {
		when(manager.sendGroupReplyReporting(any(), any(), anyString(),
				anyLong())).thenThrow(new DbException());
		startReplyTo(0);
		type("agreed");
		send();
		runIo();
		when(manager.getRecentPosts(any()))
				.thenReturn(Collections.emptyList());
		reload();
		runIo();

		holder(0).itemView.findViewById(R.id.messageEditButton).performClick();
		idle();

		assertEquals("agreed", input().getText().toString());
		assertEquals(View.GONE, activity.findViewById(
				R.id.groupReplyPreview).getVisibility());
		assertEquals(activity.getString(R.string.reply_original_unavailable),
				SnackbarProbe.text(activity));
	}

	@Test
	public void withTheComposerHiddenTheActionCopiesTheText()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("hello");
		send();
		runIo();
		List<GroupTrMember> without = new ArrayList<>();
		without.add(new GroupTrMember(creatorKey, "Creator", 0L, 0L,
				MemberRole.CREATOR));
		when(manager.getGroup(any())).thenReturn(new GroupTrState(groupId,
				"Group", new byte[32], creatorKey, "Creator", 0L, 1L, false,
				without));
		reload();
		runIo();
		assertEquals(View.GONE, activity.findViewById(R.id.composerRow)
				.getVisibility());

		TextView action = holder(1).itemView.findViewById(
				R.id.messageEditButton);
		assertEquals(activity.getString(R.string.copy_text),
				action.getText().toString());
		action.performClick();
		idle();

		android.content.ClipboardManager cm =
				(android.content.ClipboardManager) activity.getSystemService(
						android.content.Context.CLIPBOARD_SERVICE);
		assertEquals("hello", cm.getPrimaryClip().getItemAt(0).getText()
				.toString());
		assertEquals("the post is kept", 2, adapter().getItemCount());
	}

	@Test
	public void aClosedScreenDropsItsRowsWhenTheAppLocksAndSaysSoLater()
			throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("secret");
		send();
		runIo();
		assertEquals(2, adapter().getItemCount());
		controller.pause().stop();

		PendingSends.get().clear();
		idle();

		assertEquals("no unsent text is left in the closed screen", 1,
				adapter().getItemCount());
		controller.restart().resume();
		runIo();
		assertEquals(activity.getResources().getQuantityString(
				R.plurals.message_state_removed_on_lock, 1, 1),
				SnackbarProbe.text(activity));
	}

	@Test
	public void aRenameKeepsAFailedPostShownAsNotSent() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new DbException());
		type("hello");
		send();
		runIo();
		assertTrue(meta(1).endsWith(state(R.string.message_state_not_sent)));
		assertTrue(text(0, R.id.senderName).contains("Creator"));

		List<GroupTrMember> renamed = new ArrayList<>();
		renamed.add(new GroupTrMember(creatorKey, "Founder", 0L, 0L,
				MemberRole.CREATOR));
		renamed.add(new GroupTrMember(localKey, "Local", 0L, 0L));
		when(manager.getGroup(any())).thenReturn(new GroupTrState(groupId,
				"Group", new byte[32], creatorKey, "Founder", 0L, 1L, false,
				renamed));
		reload();
		runIo();

		assertTrue(text(0, R.id.senderName),
				text(0, R.id.senderName).contains("Founder"));
		assertEquals(2, adapter().getItemCount());
		assertEquals("hello", text(1, R.id.postText));
		assertTrue(meta(1).endsWith(state(R.string.message_state_not_sent)));
		View row = holder(1).itemView;
		assertNotNull(row.findViewById(R.id.bubble).getForeground());
		for (int id : new int[] {R.id.messageEditButton,
				R.id.messageRetryButton, R.id.messageDeleteButton}) {
			assertEquals(View.VISIBLE, row.findViewById(id).getVisibility());
		}
	}

	@Test
	public void theFirstLoadDoesNotRebindEveryRowForNames() throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		ProbeConversation.io.clear();
		controller = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup();
		activity = controller.get();
		List<String> changes = new ArrayList<>();
		adapter().registerAdapterDataObserver(
				new RecyclerView.AdapterDataObserver() {
					@Override
					public void onItemRangeChanged(int start, int count) {
						changes.add("changed " + start + "+" + count);
					}

					@Override
					public void onItemRangeChanged(int start, int count,
							@javax.annotation.Nullable Object payload) {
						changes.add("changed " + start + "+" + count);
					}

					@Override
					public void onItemRangeInserted(int start, int count) {
						changes.add("inserted " + start + "+" + count);
					}
				});

		runIo();

		assertEquals(Collections.singletonList("inserted 0+1"), changes);
		assertTrue(text(0, R.id.senderName).contains("Creator"));
	}

	private void reload() {
		activity.eventOccurred(new GroupTrLocalStateChangedEvent(groupId,
				GroupTrLocalStateChangedEvent.Kind.UPDATED));
	}

	private void startReplyTo(int position) throws Exception {
		holder(position).itemView.performLongClick();
		idle();
		java.lang.reflect.Field f = GroupTrConversationActivity.class
				.getDeclaredField("selectionBar");
		f.setAccessible(true);
		assertTrue(((com.professor.zerion.android.selection.SelectionBar)
				f.get(activity)).perform(com.professor.zerion.android
				.selection.SelectionAction.REPLY));
		idle();
	}

	private EditText input() {
		return activity.findViewById(R.id.messageInput);
	}

	private void type(String s) {
		input().setText(s);
	}

	private void send() {
		activity.findViewById(R.id.sendButton).performClick();
		idle();
	}

	private String state(int res) {
		return activity.getString(res);
	}

	private String meta(int position) {
		return text(position, R.id.postTime);
	}

	private String text(int position, int id) {
		return ((TextView) holder(position).itemView.findViewById(id))
				.getText().toString();
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
	}

	private RecyclerView.Adapter<?> adapter() {
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		return list.getAdapter();
	}

	private GroupTrPost post(byte[] sender, byte[] body, long timestamp,
			byte[] id) {
		return new GroupTrPost(groupId, sender, "Name", body, timestamp, 1L,
				Arrays.equals(sender, localKey), 0L, timestamp, id);
	}

	private ProbeConversation open() {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		controller = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup();
		activity = controller.get();
		runIo();
		return activity;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void runIo() {
		idle();
		while (!ProbeConversation.io.isEmpty()) {
			Runnable r = ProbeConversation.io.remove(0);
			r.run();
			idle();
		}
		idle();
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
