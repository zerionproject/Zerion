package com.professor.zerion.android.channel;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelOwnCommentHeadingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;
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
	private final byte[] ownerKey = key((byte) 1);
	private final byte[] myKey = key((byte) 5);
	private final byte[] aliceKey = key((byte) 7);
	private final byte[] otherKey = key((byte) 8);
	private final long now = System.currentTimeMillis() / HOUR * HOUR;
	private ProbeComments activity;

	@Before
	public void setUp() throws Exception {
		io.clear();
		manager = mock(ChannelManager.class);
		when(manager.getChannel(any())).thenReturn(new ChannelState(channelId,
				new byte[16], ownerKey, new byte[0], "News", "", null, HOUR,
				true, null, "", 1L, false, 0L));
		when(manager.getComments(any(), anyLong()))
				.thenReturn(Collections.emptyList());
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		when(manager.getAnnouncedSubscribers(any()))
				.thenReturn(Collections.emptyList());
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.areCommentsEnabled(any(), anyLong())).thenReturn(true);
		when(manager.getMyChannelPublicKey(any())).thenReturn(myKey);
	}

	private ProbeComments open() {
		Intent i = ChannelCommentsActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT);
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeComments.class);
		activity = Robolectric.buildActivity(ProbeComments.class, i).setup()
				.get();
		runIo();
		return activity;
	}

	private void sharedName(@Nullable String name) throws Exception {
		sharedName(name, myKey);
	}

	private void sharedName(@Nullable String name, byte[] key)
			throws Exception {
		when(manager.getAnnouncedSubscribers(any())).thenReturn(name == null
				? Collections.emptyList()
				: Collections.singletonList(new ChannelSubscriber(name,
						key, new byte[0], HOUR, false)));
	}

	private void asOwner() throws Exception {
		when(manager.getChannel(any())).thenReturn(new ChannelState(channelId,
				new byte[16], ownerKey, new byte[0], "News", "", null, HOUR,
				true, null, "", 1L, true, 0L));
		when(manager.getMyChannelPublicKey(any())).thenReturn(ownerKey);
	}

	private static ChannelQueuedItem queuedComment(long id) {
		return new ChannelQueuedItem(id, ChannelQueuedItem.Kind.COMMENT,
				PARENT, "nice one", false, System.currentTimeMillis(), false);
	}

	private void throughEveryState(@Nullable String shared) throws Exception {
		throughEveryState(shared, false);
	}

	private void throughEveryState(@Nullable String shared, boolean owner)
			throws Exception {
		byte[] me = owner ? ownerKey : myKey;
		if (owner) asOwner();
		sharedName(shared, me);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(manager.postCommentWithId(any(), anyLong(), anyString()))
				.thenAnswer(inv -> {
					entered.countDown();
					assertTrue(release.await(10, TimeUnit.SECONDS));
					throw new ChannelRequestQueuedException(false, 9L);
				});
		open();
		String you = activity.getString(R.string.channels_comment_you);
		String name = shared == null ? you : shared;
		String tag = shared == null ? "" : owner
				? activity.getString(R.string.channels_comment_owner) : you;

		input().setText("nice one");
		activity.findViewById(R.id.commentsComposeSendButton).performClick();
		idle();
		assertTrue(meta().endsWith(activity.getString(
				R.string.message_state_sending)));
		assertHeading("while sending", name, tag);

		Thread sending = new Thread(io.remove(0));
		sending.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		idle();
		assertHeading("while the send runs", name, tag);

		when(manager.getQueuedItems(any())).thenReturn(
				Collections.singletonList(queuedComment(9L)));
		release.countDown();
		sending.join(10_000);
		runIo();
		assertTrue(meta().endsWith(activity.getString(
				R.string.message_state_waiting)));
		assertHeading("while queued", name, tag);

		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		when(manager.getComments(any(), anyLong())).thenReturn(
				Collections.singletonList(new ChannelComment(PARENT, 9L,
						"nice one", shared == null ? "" : shared, me,
						new byte[0], now)));
		activity.eventOccurred(new ChannelCommentReceivedEvent(channelId,
				PARENT));
		runIo();
		assertEquals(1, adapter().getItemCount());
		assertFalse(meta().contains("·"));
		assertHeading("once delivered", name, tag);
	}

	@Test
	public void myCommentWithoutANameReadsYouInEveryState() throws Exception {
		throughEveryState(null);
	}

	@Test
	public void myCommentWithANameReadsTheNameAndYouInEveryState()
			throws Exception {
		throughEveryState("Sam");
	}

	@Test
	public void theOwnersNamedCommentCarriesTheOwnerTagInEveryState()
			throws Exception {
		throughEveryState("Boss", true);
	}

	@Test
	public void theOwnersNamelessCommentReadsYouInEveryState()
			throws Exception {
		throughEveryState(null, true);
	}

	@Test
	public void aNameThatWasGivenUpKeepsTheEarlierNameOnTheComment()
			throws Exception {
		sharedName("Sam");
		when(manager.getQueuedItems(any())).thenReturn(Arrays.asList(
				new ChannelQueuedItem(5L, ChannelQueuedItem.Kind.ANNOUNCE,
						-1L, "Ally", false, System.currentTimeMillis(), true),
				queuedComment(9L)));
		open();
		String you = activity.getString(R.string.channels_comment_you);
		assertHeading("while queued behind a given up name", "Sam", you);

		when(manager.getQueuedItems(any())).thenReturn(Collections
				.singletonList(new ChannelQueuedItem(5L,
						ChannelQueuedItem.Kind.ANNOUNCE, -1L, "Ally", false,
						System.currentTimeMillis(), true)));
		when(manager.getComments(any(), anyLong())).thenReturn(
				Collections.singletonList(new ChannelComment(PARENT, 9L,
						"nice one", "Sam", myKey, new byte[0], now)));
		activity.eventOccurred(new ChannelCommentReceivedEvent(channelId,
				PARENT));
		runIo();
		assertHeading("once delivered", "Sam", you);
	}

	@Test
	public void aCommentBehindAWaitingNameIsHeadedByThatName()
			throws Exception {
		when(manager.getQueuedItems(any())).thenReturn(Arrays.asList(
				new ChannelQueuedItem(5L, ChannelQueuedItem.Kind.ANNOUNCE,
						-1L, "Sam", false, System.currentTimeMillis(), false),
				queuedComment(9L)));
		open();
		String you = activity.getString(R.string.channels_comment_you);
		assertHeading("while queued behind the name", "Sam", you);

		sharedName("Sam");
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		when(manager.getComments(any(), anyLong())).thenReturn(
				Collections.singletonList(new ChannelComment(PARENT, 9L,
						"nice one", "Sam", myKey, new byte[0], now)));
		activity.eventOccurred(new ChannelCommentReceivedEvent(channelId,
				PARENT));
		runIo();
		assertHeading("once delivered", "Sam", you);
	}

	@Test
	public void twoOthersWithoutANameAreStillToldApartWhileIAmNamelessToo()
			throws Exception {
		when(manager.getComments(any(), anyLong())).thenReturn(Arrays.asList(
				new ChannelComment(PARENT, 1L, "a", "", aliceKey, new byte[0],
						now - 9 * DAY),
				new ChannelComment(PARENT, 2L, "b", "", otherKey, new byte[0],
						now - 2 * DAY),
				new ChannelComment(PARENT, 3L, "c", "", myKey, new byte[0],
						now - HOUR)));
		open();

		String prefix = activity.getString(
				R.string.channels_delegations_unnamed_person) + " (first wrote ";
		String first = text(0, R.id.commentAuthor);
		String second = text(1, R.id.commentAuthor);
		assertTrue(first, first.startsWith(prefix));
		assertTrue(second, second.startsWith(prefix));
		assertNotEquals(first, second);
		assertEquals(View.GONE, tagVisibility(0));
		assertEquals(View.GONE, tagVisibility(1));
		assertHeadingAt(2, activity.getString(R.string.channels_comment_you),
				"");
	}

	@Test
	public void myNamedCommentSharingANameGetsADateAndTheYouTag()
			throws Exception {
		when(manager.getComments(any(), anyLong())).thenReturn(Arrays.asList(
				new ChannelComment(PARENT, 1L, "a", "Alice", aliceKey,
						new byte[0], now - 9 * DAY),
				new ChannelComment(PARENT, 2L, "b", "Alice", myKey,
						new byte[0], now - 2 * DAY)));
		open();

		String theirs = text(0, R.id.commentAuthor);
		String mine = text(1, R.id.commentAuthor);
		assertTrue(theirs, theirs.startsWith("Alice (first wrote "));
		assertTrue(mine, mine.startsWith("Alice (first wrote "));
		assertNotEquals(theirs, mine);
		assertEquals(View.GONE, tagVisibility(0));
		assertEquals(View.VISIBLE, tagVisibility(1));
		assertEquals(activity.getString(R.string.channels_comment_you),
				text(1, R.id.commentAuthorTag));
	}

	private void assertHeading(String when, String name, String tag) {
		assertEquals(when, 1, adapter().getItemCount());
		assertEquals(when, name, text(0, R.id.commentAuthor));
		if (tag.isEmpty()) {
			assertEquals(when + ": no tag", View.GONE, tagVisibility(0));
		} else {
			assertEquals(when + ": tag shown", View.VISIBLE, tagVisibility(0));
			assertEquals(when, tag, text(0, R.id.commentAuthorTag));
		}
	}

	private void assertHeadingAt(int position, String name, String tag) {
		assertEquals(name, text(position, R.id.commentAuthor));
		if (tag.isEmpty()) {
			assertEquals(View.GONE, tagVisibility(position));
		} else {
			assertEquals(tag, text(position, R.id.commentAuthorTag));
		}
	}

	private EditText input() {
		return activity.findViewById(R.id.commentsComposeInput);
	}

	private String meta() {
		return text(0, R.id.commentTimestamp);
	}

	private int tagVisibility(int position) {
		return holder(position).itemView.findViewById(R.id.commentAuthorTag)
				.getVisibility();
	}

	private String text(int position, int id) {
		return ((TextView) holder(position).itemView.findViewById(id))
				.getText().toString();
	}

	private RecyclerView.Adapter<?> adapter() {
		RecyclerView list = activity.findViewById(R.id.commentsRecycler);
		return list.getAdapter();
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
