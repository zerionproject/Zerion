package com.professor.zerion.android.channel;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.SnackbarProbe;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelNoAccessException;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.ChannelSentNotSavedException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelCommentSendStateTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long PARENT = 4L;

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager;

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

	private final byte[] channelId = new byte[32];
	private final byte[] myKey = key((byte) 5);
	private ActivityController<ProbeComments> controller;
	private ProbeComments activity;

	@Before
	public void setUp() throws Exception {
		io.clear();
		manager = mock(ChannelManager.class);
		ChannelState s = new ChannelState(channelId, new byte[16],
				new byte[32], new byte[0], "News", "", null, HOUR, true, null,
				"", 1L, false, 0L);
		when(manager.getChannel(any())).thenReturn(s);
		when(manager.getComments(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.areCommentsEnabled(any(), anyLong())).thenReturn(true);
		when(manager.getMyChannelPublicKey(any())).thenReturn(myKey);
		activity = open();
	}

	private ProbeComments open() {
		Intent i = ChannelCommentsActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT);
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeComments.class);
		controller = Robolectric.buildActivity(ProbeComments.class, i)
				.setup();
		ProbeComments a = controller.get();
		runIo();
		return a;
	}

	private ChannelComment comment(long id, String body) {
		return new ChannelComment(PARENT, id, body, "Me", myKey, new byte[0],
				0L);
	}

	private ChannelQueuedItem queuedItem(long id, String text,
			boolean notSent) {
		return new ChannelQueuedItem(id, ChannelQueuedItem.Kind.COMMENT,
				PARENT, text, false, System.currentTimeMillis(), notSent);
	}

	@Test
	public void aCommentShowsAtOnceWhileItIsBeingSent() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenAnswer(inv -> {
					entered.countDown();
					assertTrue(release.await(10, TimeUnit.SECONDS));
					throw new ChannelRequestQueuedException(false, 9L);
				});

		type("nice one");
		send();

		assertEquals("", input().getText().toString());
		assertEquals("the comment is on screen before it is sent", 1,
				adapter().getItemCount());
		assertEquals("nice one", text(0, R.id.commentBody));
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));
		verify(manager, never()).postCommentWithId(any(), anyLong(),
				anyString());

		Thread sending = new Thread(io.remove(0));
		sending.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		idle();
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));

		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(queuedItem(9L, "nice one", false)));
		release.countDown();
		sending.join(10_000);
		runIo();

		assertEquals("the queued comment replaces the sending row", 1,
				adapter().getItemCount());
		assertTrue(meta(0).endsWith(state(R.string.message_state_waiting)));
		assertEquals(activity.getString(
				R.string.channels_comment_queued_toast),
				SnackbarProbe.text(activity));
	}

	@Test
	public void aSentCommentBecomesTheRealComment() throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenReturn(77L);
		type("nice one");
		send();
		when(manager.getComments(any(), anyLong())).thenReturn(
				Collections.singletonList(comment(77L, "nice one")));
		runIo();

		assertEquals(1, adapter().getItemCount());
		assertFalse(meta(0).contains("·"));
		assertEquals(View.GONE, holder(0).itemView.findViewById(
				R.id.messageNotSentActions).getVisibility());
	}

	@Test
	public void aSendingCommentShowsTheHourItIsKeptUnder() throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenReturn(77L);
		long before = System.currentTimeMillis();
		type("nice one");
		send();
		String sending = meta(0);
		long after = System.currentTimeMillis();
		org.junit.Assume.assumeTrue(before / HOUR == after / HOUR);
		long kept = after / HOUR * HOUR;
		when(manager.getComments(any(), anyLong())).thenReturn(
				Collections.singletonList(new ChannelComment(PARENT, 77L,
						"nice one", "Me", myKey, new byte[0], kept)));
		runIo();

		String stored = meta(0);
		assertEquals(com.professor.zerion.android.util.UiUtils
				.formatChannelHour(activity, kept), stored);
		assertTrue(stored, stored.startsWith("around "));
		assertEquals("the time does not jump once the comment is stored",
				activity.getString(R.string.message_state_meta, stored,
						state(R.string.message_state_sending)), sending);
	}

	@Test
	public void twoIdenticalCommentsEachShowUntilTheirOwnArrives()
			throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenReturn(1L, 2L);
		type("ok");
		send();
		io.remove(0).run();
		type("ok");
		send();
		when(manager.getComments(any(), anyLong())).thenReturn(
				Collections.singletonList(comment(1L, "ok")));
		Runnable refresh = io.remove(0);
		refresh.run();
		idle();

		assertEquals("the second comment still shows while it is sent", 2,
				adapter().getItemCount());
		assertTrue(meta(1).endsWith(state(R.string.message_state_sending)));

		when(manager.getComments(any(), anyLong())).thenReturn(Arrays.asList(
				comment(1L, "ok"), comment(2L, "ok")));
		runIo();
		assertEquals(2, adapter().getItemCount());
		assertFalse(meta(1).contains("·"));
	}

	@Test
	public void aFailedCommentKeepsItsTextAndOffersEditRetryAndDelete()
			throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenThrow(new DbException());

		type("nice one");
		send();
		runIo();

		assertEquals(1, adapter().getItemCount());
		assertEquals("nice one", text(0, R.id.commentBody));
		assertTrue(meta(0).endsWith(state(R.string.message_state_not_sent)));
		View row = holder(0).itemView;
		assertNotNull(row.findViewById(R.id.commentCard).getForeground());
		for (int id : new int[] {R.id.messageEditButton,
				R.id.messageRetryButton, R.id.messageDeleteButton}) {
			assertEquals(View.VISIBLE, row.findViewById(id).getVisibility());
		}
		assertEquals(activity.getString(R.string.channels_comments_failed),
				SnackbarProbe.text(activity));
		TextView action = SnackbarProbe.action(activity);
		assertNotNull(action);
		assertEquals(activity.getString(R.string.message_state_retry),
				action.getText().toString());
	}

	@Test
	public void retryingSendsTheCommentExactlyOnceMore() throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenThrow(new DbException()).thenReturn(3L);
		type("nice one");
		send();
		runIo();

		View retry = holder(0).itemView.findViewById(R.id.messageRetryButton);
		retry.performClick();
		retry.performClick();
		idle();
		TextView action = SnackbarProbe.action(activity);
		if (action != null) action.performClick();
		idle();
		runIo();

		verify(manager, times(2)).postCommentWithId(eq(channelId),
				eq(PARENT), eq("nice one"));
	}

	@Test
	public void aCommentTheChannelRefusesGoesBackIntoTheComposer()
			throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenThrow(new ChannelNoAccessException());
		type("nice one");
		send();
		runIo();

		assertEquals("nice one", input().getText().toString());
		assertEquals(0, adapter().getItemCount());
		assertEquals(activity.getString(R.string.channels_no_access_toast),
				SnackbarProbe.text(activity));
		assertNull("retrying cannot help", SnackbarProbe.action(activity));
	}

	@Test
	public void aCommentRefusedWhileAwayComesBackWithItsReason()
			throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenThrow(new ChannelNoAccessException());
		type("nice one");
		send();
		controller.pause().stop();
		io.remove(io.size() - 1).run();
		idle();
		controller.restart().resume();
		runIo();

		assertEquals("nice one", input().getText().toString());
		assertEquals(0, adapter().getItemCount());
		assertEquals("the reason is shown once", 1, Collections.frequency(
				SnackbarProbe.texts(activity),
				activity.getString(R.string.channels_no_access_toast)));
	}

	@Test
	public void aCommentTheOwnerTookIsNotShownAsNotSent() throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenThrow(new ChannelSentNotSavedException(55L));
		type("nice one");
		send();
		runIo();

		assertNull(SnackbarProbe.text(activity));
		assertEquals(1, adapter().getItemCount());
		assertTrue(meta(0).endsWith(state(R.string.message_state_sent)));
		assertEquals(View.GONE, holder(0).itemView.findViewById(
				R.id.messageNotSentActions).getVisibility());

		when(manager.getComments(any(), anyLong())).thenReturn(
				Collections.singletonList(comment(55L, "nice one")));
		activity = open();
		assertEquals(1, adapter().getItemCount());
		assertFalse(meta(0).contains("·"));
	}

	@Test
	public void aQueuedCommentThatFailedCanBeRetriedOnce() throws Exception {
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(queuedItem(9L, "nice one", true)));
		activity = open();
		assertTrue(meta(0).endsWith(state(R.string.message_state_not_sent)));

		View retry = holder(0).itemView.findViewById(R.id.messageRetryButton);
		retry.performClick();
		retry.performClick();
		idle();
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));
		runIo();

		verify(manager, times(1)).retryQueuedItem(channelId, 9L);
	}

	@Test
	public void retryingACommentThatWasAlreadySentSaysNothing()
			throws Exception {
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(queuedItem(9L, "nice one", true)));
		activity = open();
		doThrow(new DbException()).when(manager).retryQueuedItem(any(),
				anyLong());
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());

		holder(0).itemView.findViewById(R.id.messageRetryButton)
				.performClick();
		runIo();

		assertNull(SnackbarProbe.text(activity));
	}

	@Test
	public void editingAQueuedCommentMovesItsTextBack() throws Exception {
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(queuedItem(9L, "nice one", true)));
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenReturn(ChannelQueuedItem.DeleteResult.DELETED);
		activity = open();

		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		holder(0).itemView.findViewById(R.id.messageEditButton)
				.performClick();
		runIo();

		verify(manager).deleteQueuedItem(channelId, 9L);
		assertEquals("nice one", input().getText().toString());
		assertEquals(0, adapter().getItemCount());
	}

	@Test
	public void deletingAQueuedCommentCanBeUndone() throws Exception {
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(queuedItem(9L, "nice one", true)));
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenReturn(ChannelQueuedItem.DeleteResult.DELETED);
		activity = open();

		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		holder(0).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		runIo();
		assertEquals(0, adapter().getItemCount());
		TextView undo = SnackbarProbe.action(activity);
		assertNotNull(undo);
		undo.performClick();
		idle();

		assertEquals(1, adapter().getItemCount());
		assertEquals("nice one", text(0, R.id.commentBody));
		assertTrue(meta(0).endsWith(state(R.string.message_state_not_sent)));
		assertEquals(View.VISIBLE, holder(0).itemView.findViewById(
				R.id.messageRetryButton).getVisibility());
	}

	private ProbeComments openWithAFailedQueuedComment() throws Exception {
		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(queuedItem(9L, "nice one", true)));
		return open();
	}

	@Test
	public void aDeleteThatFailsKeepsTheCommentAndSaysSo() throws Exception {
		activity = openWithAFailedQueuedComment();
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenThrow(new DbException());

		holder(0).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		runIo();

		assertEquals(activity.getString(R.string.delete_failed),
				SnackbarProbe.text(activity));
		assertNull("nothing to undo", SnackbarProbe.action(activity));
		assertEquals(1, adapter().getItemCount());
		assertTrue(meta(0).endsWith(state(R.string.message_state_not_sent)));
	}

	@Test
	public void aCommentThatMayAlreadyBeSentIsNotOfferedAgain()
			throws Exception {
		activity = openWithAFailedQueuedComment();
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenReturn(ChannelQueuedItem.DeleteResult.GONE);
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());

		holder(0).itemView.findViewById(R.id.messageEditButton)
				.performClick();
		runIo();

		assertEquals(activity.getString(R.string.message_state_maybe_sent),
				SnackbarProbe.text(activity));
		assertNull(SnackbarProbe.action(activity));
		assertEquals("", input().getText().toString());
		assertEquals(0, adapter().getItemCount());
	}

	@Test
	public void aDoubleTapOnEditMovesTheTextOnce() throws Exception {
		activity = openWithAFailedQueuedComment();
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenReturn(ChannelQueuedItem.DeleteResult.DELETED);
		View edit = holder(0).itemView.findViewById(R.id.messageEditButton);
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());

		edit.performClick();
		edit.performClick();
		runIo();

		verify(manager, times(1)).deleteQueuedItem(channelId, 9L);
		assertEquals("nice one", input().getText().toString());
	}

	@Test
	public void anEditRefusedWhileADeleteRunsCanBeTappedAgain()
			throws Exception {
		activity = openWithAFailedQueuedComment();
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenThrow(new DbException());
		holder(0).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		holder(0).itemView.findViewById(R.id.messageEditButton)
				.performClick();
		idle();

		assertTrue("edit is not left greyed out", holder(0).itemView
				.findViewById(R.id.messageEditButton).isEnabled());
		runIo();
		verify(manager, times(1)).deleteQueuedItem(channelId, 9L);
	}

	@Test
	public void aQueuedRetryRefusedBecauseOneRunsShowsThatAtOnce()
			throws Exception {
		activity = openWithAFailedQueuedComment();
		View retry = holder(0).itemView.findViewById(R.id.messageRetryButton);
		String scope = ChannelCommentsActivity.queueScope(channelId);
		onAnotherThread(() ->
				PendingSends.get().beginQueuedRetry(scope, 9L));

		retry.performClick();

		assertNotStuck(holder(0).itemView.findViewById(
				R.id.messageRetryButton));
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));
		verify(manager, never()).retryQueuedItem(any(), anyLong());
		PendingSends.get().endQueuedRetry(scope, 9L);
	}

	@Test
	public void anEditRefusedBecauseTheSendRunsShowsThatAtOnce()
			throws Exception {
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenThrow(new DbException());
		type("nice one");
		send();
		runIo();
		View edit = holder(0).itemView.findViewById(R.id.messageEditButton);
		PendingSends.Entry e = PendingSends.get().entries(
				PendingSends.channelCommentScope(channelId, PARENT)).get(0);
		onAnotherThread(() -> PendingSends.get().retry(e, r -> {
		}));

		edit.performClick();

		assertNotStuck(holder(0).itemView.findViewById(
				R.id.messageEditButton));
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));
		assertEquals("", input().getText().toString());
	}

	@Test
	public void undoingADeleteDoesNotLookLikeANewFailure() throws Exception {
		activity = openWithAFailedQueuedComment();
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenReturn(ChannelQueuedItem.DeleteResult.DELETED);
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		holder(0).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		runIo();

		SnackbarProbe.action(activity).performClick();
		idle();

		String failure = activity.getString(R.string.channels_comments_failed);
		for (String shown : SnackbarProbe.texts(activity)) {
			assertFalse(shown, shown.equals(failure)
					|| shown.startsWith("Could not send"));
		}
		assertEquals(1, adapter().getItemCount());
	}

	@Test
	public void withCommentsOffTheActionCopiesTheText() throws Exception {
		when(manager.areCommentsEnabled(any(), anyLong())).thenReturn(false);
		activity = openWithAFailedQueuedComment();
		assertEquals(View.GONE, activity.findViewById(
				R.id.commentsComposeBar).getVisibility());

		TextView action = holder(0).itemView.findViewById(
				R.id.messageEditButton);
		assertEquals(activity.getString(R.string.copy_text),
				action.getText().toString());
		action.performClick();
		runIo();

		android.content.ClipboardManager cm =
				(android.content.ClipboardManager) activity.getSystemService(
						android.content.Context.CLIPBOARD_SERVICE);
		assertEquals("nice one", cm.getPrimaryClip().getItemAt(0).getText()
				.toString());
		verify(manager, never()).deleteQueuedItem(any(), anyLong());
	}

	private EditText input() {
		return activity.findViewById(R.id.commentsComposeInput);
	}

	private static void onAnotherThread(Runnable r) throws Exception {
		Thread t = new Thread(r);
		t.start();
		t.join();
	}

	private static void assertNotStuck(View button) {
		assertFalse("a tapped action is not left greyed out",
				button.getVisibility() == View.VISIBLE && !button.isEnabled());
	}


	private void type(String s) {
		input().setText(s);
	}

	private void send() {
		activity.findViewById(R.id.commentsComposeSendButton).performClick();
		idle();
	}

	private String state(int res) {
		return activity.getString(res);
	}

	private String meta(int position) {
		return text(position, R.id.commentTimestamp);
	}

	private String text(int position, int id) {
		return ((TextView) holder(position).itemView.findViewById(id))
				.getText().toString();
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = activity.findViewById(R.id.commentsRecycler);
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
		RecyclerView list = activity.findViewById(R.id.commentsRecycler);
		return list.getAdapter();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void runIo() {
		idle();
		for (int round = 0; round < 20 && !io.isEmpty(); round++) {
			List<Runnable> pending = new ArrayList<>(io);
			io.clear();
			for (Runnable r : pending) r.run();
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
