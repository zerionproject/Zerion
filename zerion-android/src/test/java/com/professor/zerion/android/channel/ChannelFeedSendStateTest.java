package com.professor.zerion.android.channel;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
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
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelPostTooLongException;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelRefusedException;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
public class ChannelFeedSendStateTest {

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

	private final byte[] channelId = new byte[32];
	private ActivityController<ProbeFeed> controller;
	private ProbeFeed feed;

	private void stub(boolean owner) throws Exception {
		io.clear();
		manager = mock(ChannelManager.class);
		ChannelState s = new ChannelState(channelId, new byte[16],
				new byte[32], new byte[0], "News", "", null, HOUR, true, null,
				"", 1L, owner, 0L);
		when(manager.getChannel(any())).thenReturn(s);
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		when(manager.canPost(any())).thenReturn(true);
		when(manager.getCommentExceptions(any()))
				.thenReturn(Collections.emptySet());
		when(manager.getMyChannelPublicKey(any())).thenReturn(new byte[32]);
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.refreshChannelTick(any())).thenReturn(true);
	}

	@Before
	public void setUp() throws Exception {
		stub(true);
	}

	private ProbeFeed open() {
		Intent i = ChannelFeedActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId);
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeFeed.class);
		controller = Robolectric.buildActivity(ProbeFeed.class, i).setup();
		ProbeFeed f = controller.get();
		feed = f;
		runIo();
		return f;
	}

	private static ChannelPost post(long seq, String body) {
		return new ChannelPost(new byte[32], seq, new byte[32],
				System.currentTimeMillis() / HOUR * HOUR, body,
				Collections.emptyList(), 0L, new byte[64], true);
	}

	private static ChannelQueuedItem queuedPost(long id, boolean notSent) {
		return new ChannelQueuedItem(id, ChannelQueuedItem.Kind.POST, -1L,
				"Friday notes", false, System.currentTimeMillis(), notSent);
	}

	@Test
	public void anOwnersPostShowsAtOnceWhileItIsBeingSent() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenAnswer(inv -> {
					entered.countDown();
					assertTrue(release.await(10, TimeUnit.SECONDS));
					return 1L;
				});
		open();

		type("Friday notes");
		send();

		assertEquals("", input().getText().toString());
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
		assertEquals(View.VISIBLE, list.getVisibility());
		assertEquals("the post is on screen before it is sent", 1,
				adapter().getItemCount());
		assertEquals("Friday notes", text(0, R.id.channelPostBodyView));
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));
		verify(manager, never()).publishPostWithSeq(any(), anyString(),
				anyLong());

		Thread sending = new Thread(io.remove(io.size() - 1));
		sending.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		idle();
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));

		when(manager.getRecentPosts(any(), anyLong())).thenReturn(
				Collections.singletonList(post(1L, "Friday notes")));
		release.countDown();
		sending.join(10_000);
		runIo();

		assertEquals("the stored post replaces the sending row", 1,
				adapter().getItemCount());
		assertFalse(meta(0).contains("·"));
	}

	@Test
	public void anEditorPostTheOwnerTookStaysUntilItArrives()
			throws Exception {
		stub(false);
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenReturn(5L);
		open();

		type("Friday notes");
		send();
		runIo();

		assertEquals("the post the owner took is still shown", 1,
				adapter().getItemCount());
		assertTrue(meta(0).endsWith(state(R.string.message_state_sent)));

		when(manager.getRecentPosts(any(), anyLong())).thenReturn(
				Collections.singletonList(post(5L, "Friday notes")));
		open();
		assertEquals(1, adapter().getItemCount());
		assertFalse(meta(0).contains("·"));
	}

	@Test
	public void anEditorsQueuedPostShowsAsARow() throws Exception {
		stub(false);
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenThrow(new ChannelRequestQueuedException(false, 3L));
		open();
		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(queuedPost(3L, false)));

		type("Friday notes");
		send();
		runIo();

		assertEquals(1, adapter().getItemCount());
		assertEquals("Friday notes", text(0, R.id.channelPostBodyView));
		assertTrue(meta(0).endsWith(state(R.string.message_state_waiting)));
		TextView notice = feed.findViewById(R.id.channelNoticeBanner);
		assertFalse("the waiting post is a row, not only a banner",
				notice.getVisibility() == View.VISIBLE
						&& notice.getText().toString().equals(feed.getString(
								R.string.channels_queued_notice_waiting)));
		assertEquals(feed.getString(R.string.channels_post_queued_toast),
				SnackbarProbe.text(feed));
	}

	@Test
	public void aQueuedPostThatWasNotSentCanBeRetriedOnce() throws Exception {
		stub(false);
		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(queuedPost(3L, true)));
		open();

		assertEquals(1, adapter().getItemCount());
		assertTrue(meta(0).endsWith(state(R.string.message_state_not_sent)));
		View row = holder(0).itemView;
		assertNotNull(row.findViewById(R.id.layout).getForeground());
		View retry = row.findViewById(R.id.messageRetryButton);
		assertEquals(View.VISIBLE, retry.getVisibility());
		assertEquals(View.VISIBLE, row.findViewById(
				R.id.messageEditButton).getVisibility());
		retry.performClick();
		retry.performClick();
		idle();
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));
		runIo();

		verify(manager, times(1)).retryQueuedItem(channelId, 3L);
	}

	@Test
	public void aFailedPostOffersRetryInTheSnackbarAndResendsOnce()
			throws Exception {
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenThrow(new DbException()).thenReturn(1L);
		open();

		type("Friday notes");
		send();
		runIo();

		assertEquals(1, adapter().getItemCount());
		assertTrue(meta(0).endsWith(state(R.string.message_state_not_sent)));
		assertEquals(feed.getString(R.string.channels_compose_error_publish),
				SnackbarProbe.text(feed));
		TextView action = SnackbarProbe.action(feed);
		assertNotNull(action);
		assertEquals(feed.getString(R.string.message_state_retry),
				action.getText().toString());
		action.performClick();
		holder(0).itemView.findViewById(R.id.messageRetryButton)
				.performClick();
		runIo();

		verify(manager, times(2)).publishPostWithSeq(eq(channelId),
				eq("Friday notes"), eq(0L));
	}

	@Test
	public void aRefusedEditorPostGoesBackIntoTheComposerWithoutRetry()
			throws Exception {
		stub(false);
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenThrow(new ChannelRefusedException());
		open();

		type("Friday notes");
		send();
		runIo();

		assertEquals("Friday notes", input().getText().toString());
		assertEquals(0, adapter().getItemCount());
		assertEquals(feed.getString(R.string.channels_editor_post_not_sent),
				SnackbarProbe.text(feed));
		assertNull(SnackbarProbe.action(feed));
	}

	@Test
	public void aPostRefusedWhileAwayComesBackWithItsReason()
			throws Exception {
		stub(false);
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenThrow(new ChannelRefusedException());
		open();

		type("Friday notes");
		send();
		controller.pause().stop();
		io.remove(io.size() - 1).run();
		idle();
		controller.restart().resume();
		runIo();

		assertEquals("Friday notes", input().getText().toString());
		assertEquals(0, adapter().getItemCount());
		assertEquals("the reason is shown once", 1, Collections.frequency(
				SnackbarProbe.texts(feed),
				feed.getString(R.string.channels_editor_post_not_sent)));
	}

	@Test
	public void aPostThatIsTooLongGoesBackIntoTheComposer() throws Exception {
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenThrow(new ChannelPostTooLongException());
		open();

		type("Friday notes");
		send();
		runIo();

		assertEquals("Friday notes", input().getText().toString());
		assertEquals(0, adapter().getItemCount());
		assertEquals(feed.getString(R.string.text_too_long),
				SnackbarProbe.text(feed));
	}

	@Test
	public void aSendIntoAnEmptiedFeedDoesNotListThePostsThatWent()
			throws Exception {
		when(manager.getRecentPosts(any(), anyLong())).thenReturn(
				Collections.singletonList(post(1L, "older")));
		open();
		assertEquals(1, adapter().getItemCount());
		when(manager.getRecentPosts(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		feed.eventOccurred(new ChannelCommentReceivedEvent(channelId, 1L));
		runIo();
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
		assertEquals(View.GONE, list.getVisibility());

		type("Friday notes");
		send();

		assertEquals("only the sending card is listed", 1,
				adapter().getItemCount());
		assertEquals("Friday notes", text(0, R.id.channelPostBodyView));
	}

	@Test
	public void deletingAPostThatFailedCanBeUndone() throws Exception {
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenThrow(new DbException());
		when(manager.getRecentPosts(any(), anyLong())).thenReturn(
				Collections.singletonList(post(1L, "older")));
		open();
		type("Friday notes");
		send();
		runIo();
		assertEquals(2, adapter().getItemCount());

		holder(1).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		idle();
		assertEquals(1, adapter().getItemCount());
		TextView undo = SnackbarProbe.action(feed);
		assertNotNull(undo);
		assertEquals(feed.getString(R.string.message_state_undo),
				undo.getText().toString());
		undo.performClick();
		idle();
		assertEquals(2, adapter().getItemCount());
	}

	@Test
	public void aSendThatFailsLaterDoesNotPullTheReaderDown()
			throws Exception {
		List<ChannelPost> posts = new ArrayList<>();
		for (int i = 1; i <= 40; i++) posts.add(post(i, "post " + i));
		when(manager.getRecentPosts(any(), anyLong())).thenReturn(posts);
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenThrow(new DbException());
		open();
		type("Friday notes");
		send();
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
		layout(list);
		list.scrollToPosition(0);
		layout(list);
		LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
		assertEquals(0, lm.findFirstVisibleItemPosition());

		runIo();
		layout(list);

		assertEquals("the reader stays where they were", 0,
				lm.findFirstVisibleItemPosition());
	}

	private static ChannelQueuedItem timedPost(long id) {
		return new ChannelQueuedItem(id, ChannelQueuedItem.Kind.POST, -1L,
				"Friday notes", false, System.currentTimeMillis(), true,
				ChannelQueuedItem.Reason.REFUSED, 3600L);
	}

	@Test
	public void undoingTheDeleteOfAQueuedPostKeepsItsTimer()
			throws Exception {
		stub(false);
		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(timedPost(3L)));
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenReturn(ChannelQueuedItem.DeleteResult.DELETED);
		open();
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());

		holder(0).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		runIo();
		SnackbarProbe.action(feed).performClick();
		idle();
		holder(0).itemView.findViewById(R.id.messageRetryButton)
				.performClick();
		runIo();

		verify(manager).publishPostWithSeq(eq(channelId), eq("Friday notes"),
				eq(3600L));
	}

	@Test
	public void editingAQueuedPostKeepsItsTimer() throws Exception {
		stub(false);
		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(timedPost(3L)));
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenReturn(ChannelQueuedItem.DeleteResult.DELETED);
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenReturn(4L);
		open();
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());

		holder(0).itemView.findViewById(R.id.messageEditButton)
				.performClick();
		runIo();
		assertEquals("Friday notes", input().getText().toString());
		send();
		runIo();

		verify(manager).publishPostWithSeq(eq(channelId), eq("Friday notes"),
				eq(3600L));
	}

	@Test
	public void anEditRefusedWhileADeleteRunsCanBeTappedAgain()
			throws Exception {
		stub(false);
		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(queuedPost(3L, true)));
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenThrow(new DbException());
		open();

		holder(0).itemView.findViewById(R.id.messageDeleteButton)
				.performClick();
		holder(0).itemView.findViewById(R.id.messageEditButton)
				.performClick();
		idle();

		assertTrue("edit is not left greyed out", holder(0).itemView
				.findViewById(R.id.messageEditButton).isEnabled());
		runIo();
		verify(manager, times(1)).deleteQueuedItem(channelId, 3L);
	}

	@Test
	public void aQueuedRetryRefusedBecauseOneRunsShowsThatAtOnce()
			throws Exception {
		stub(false);
		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(queuedPost(3L, true)));
		open();
		View retry = holder(0).itemView.findViewById(R.id.messageRetryButton);
		String scope = ChannelCommentsActivity.queueScope(channelId);
		onAnotherThread(() ->
				PendingSends.get().beginQueuedRetry(scope, 3L));

		retry.performClick();

		assertNotStuck(holder(0).itemView.findViewById(
				R.id.messageRetryButton));
		assertTrue(meta(0).endsWith(state(R.string.message_state_sending)));
		verify(manager, never()).retryQueuedItem(any(), anyLong());
		PendingSends.get().endQueuedRetry(scope, 3L);
	}

	@Test
	public void aQueuedDeleteRefusedWhileOneRunsCanBeTappedAgain()
			throws Exception {
		stub(false);
		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(queuedPost(3L, true)));
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenThrow(new DbException());
		open();
		View delete = holder(0).itemView.findViewById(
				R.id.messageDeleteButton);
		delete.performClick();
		delete.performClick();

		assertNotStuck(holder(0).itemView.findViewById(
				R.id.messageDeleteButton));
		runIo();
		verify(manager, times(1)).deleteQueuedItem(channelId, 3L);
	}

	@Test
	public void takingAPostBackNeverLoosensTheTimerPicked() throws Exception {
		stub(false);
		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(queuedPost(3L, true)));
		when(manager.deleteQueuedItem(any(), anyLong()))
				.thenReturn(ChannelQueuedItem.DeleteResult.DELETED);
		when(manager.publishPostWithSeq(any(), anyString(), anyLong()))
				.thenReturn(4L);
		open();
		pickTimer(R.string.channels_ttl_one_hour);
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());

		holder(0).itemView.findViewById(R.id.messageEditButton)
				.performClick();
		runIo();
		assertEquals("Friday notes", input().getText().toString());
		assertTrue(SnackbarProbe.texts(feed).contains(feed.getString(
				R.string.channels_ttl_kept_shorter)));
		send();
		runIo();

		verify(manager).publishPostWithSeq(eq(channelId), eq("Friday notes"),
				eq(3600L));
	}

	@Test
	public void theStricterTimerIsTheShorterOneThatIsOn() {
		assertEquals(3600L, ChannelFeedActivity.stricterTimer(0L, 3600L));
		assertEquals(3600L, ChannelFeedActivity.stricterTimer(3600L, 0L));
		assertEquals(3600L, ChannelFeedActivity.stricterTimer(86400L,
				3600L));
		assertEquals(3600L, ChannelFeedActivity.stricterTimer(3600L,
				86400L));
		assertEquals(0L, ChannelFeedActivity.stricterTimer(0L, 0L));
	}

	private void pickTimer(int label) {
		feed.findViewById(R.id.channelComposeTtlButton).performClick();
		idle();
		android.widget.PopupMenu popup = org.robolectric.shadows
				.ShadowPopupMenu.getLatestPopupMenu();
		assertNotNull(popup);
		android.view.Menu menu = popup.getMenu();
		for (int i = 0; i < menu.size(); i++) {
			if (menu.getItem(i).getTitle().toString().equals(
					feed.getString(label))) {
				shadowOf(popup).getOnMenuItemClickListener()
						.onMenuItemClick(menu.getItem(i));
				return;
			}
		}
		fail("no timer " + feed.getString(label));
	}

	@Test
	public void anEditOrDeleteThatWentOutIsNotReportedAsFailed()
			throws Exception {
		when(manager.getRecentPosts(any(), anyLong())).thenReturn(
				Collections.singletonList(post(1L, "older")));
		org.mockito.Mockito.doThrow(new org.zerionproject.app.api.channel
				.ChannelSentNotSavedException(2L)).when(manager)
				.editPost(any(), anyLong(), anyString());
		org.mockito.Mockito.doThrow(new org.zerionproject.app.api.channel
				.ChannelSentNotSavedException(3L)).when(manager)
				.deletePost(any(), anyLong());
		open();

		java.lang.reflect.Method edit = ChannelFeedActivity.class
				.getDeclaredMethod("handleEdit", ChannelPost.class,
						String.class);
		edit.setAccessible(true);
		edit.invoke(feed, post(1L, "older"), "newer");
		java.lang.reflect.Method delete = ChannelFeedActivity.class
				.getDeclaredMethod("handleDelete", ChannelPost.class);
		delete.setAccessible(true);
		delete.invoke(feed, post(1L, "older"));
		runIo();

		for (String shown : SnackbarProbe.texts(feed)) {
			assertFalse(shown, shown.equals(feed.getString(
					R.string.channels_post_edit_failed))
					|| shown.equals(feed.getString(
							R.string.channels_post_delete_failed)));
		}
	}

	private static void layout(RecyclerView list) {
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
	}

	private EditText input() {
		return feed.findViewById(R.id.channelComposeInput);
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
		feed.findViewById(R.id.channelComposeSendButton).performClick();
		idle();
	}

	private String state(int res) {
		return feed.getString(res);
	}

	private String meta(int position) {
		return text(position, R.id.channelPostTimestampView);
	}

	private String text(int position, int id) {
		return ((TextView) holder(position).itemView.findViewById(id))
				.getText().toString();
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
		layout(list);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
	}

	private RecyclerView.Adapter<?> adapter() {
		RecyclerView list = feed.findViewById(R.id.channelFeedRecycler);
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
}
