package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.view.ZerionRecyclerView;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecretNoteCountdownsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final ContactId CONTACT = new ContactId(5);
	private static final long TIMER = DAYS.toMillis(7);
	private static final String FIRST = "meet at the old mill";
	private static final String SECOND = "bring the blue folder";
	private static final String THIRD = "the code is 4471";

	public static class ProbeConversation extends ConversationActivity {

		static final List<MessageId> burned = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			LockManager lock = mock(LockManager.class);
			when(lock.isLocked()).thenReturn(false);
			when(lock.isLockable()).thenReturn(new MutableLiveData<>(false));
			lockManager = lock;
			notificationManager = mock(AndroidNotificationManager.class);
		}

		@Override
		public void onSecretNoteOpened(MessageId messageId) {
			burned.add(messageId);
		}
	}

	public static class BurningConversation extends ConversationActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			LockManager lock = mock(LockManager.class);
			when(lock.isLocked()).thenReturn(false);
			when(lock.isLockable()).thenReturn(new MutableLiveData<>(false));
			lockManager = lock;
			notificationManager = mock(AndroidNotificationManager.class);
		}
	}

	private ActivityController<? extends ConversationActivity> controller;
	private ConversationActivity activity;
	private ConversationAdapter adapter;
	private final GroupId group = new GroupId(getRandomId());
	private final MessageId a = new MessageId(getRandomId());
	private final MessageId b = new MessageId(getRandomId());
	private final MessageId c = new MessageId(getRandomId());
	private final List<PrivateMessageHeader> headers = new ArrayList<>();

	private static Object field(Object o, String name) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private void pass(long millis) throws Exception {
		long left = millis;
		while (left > 0) {
			long step = Math.min(100, left);
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step));
			layout();
			left -= step;
		}
	}

	@Before
	public void setUp() throws Exception {
		ProbeConversation.burned.clear();
		open(ProbeConversation.class);
	}

	private void open(Class<? extends ConversationActivity> type)
			throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				type);
		i.putExtra(ConversationActivity.CONTACT_ID, CONTACT.getInt());
		controller = Robolectric.buildActivity(type, i).setup();
		activity = controller.get();
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
	}

	private ConversationViewModel watchModel() throws Exception {
		Field f = ConversationActivity.class.getDeclaredField("viewModel");
		f.setAccessible(true);
		ConversationViewModel watched =
				spy((ConversationViewModel) f.get(activity));
		doNothing().when(watched).deleteMessages(any());
		doNothing().when(watched).startSecretNoteCountdown(any(), anyInt());
		f.set(activity, watched);
		return watched;
	}

	@SuppressWarnings("unchecked")
	private <T> void fire(String name, T value) throws Exception {
		Object viewModel = field(activity, "viewModel");
		Field f = ConversationViewModel.class.getDeclaredField(name);
		f.setAccessible(true);
		((MutableLiveEvent<T>) f.get(viewModel)).setEvent(value);
		idle();
		layout();
	}

	@SuppressWarnings("unchecked")
	private void arrive(MessageId id, long time, String text)
			throws Exception {
		Map<MessageId, String> texts =
				(Map<MessageId, String>) field(activity, "textCache");
		texts.put(id, text);
		headers.add(new PrivateMessageHeader(id, group, time, false, true,
				true, false, true, Collections.emptyList(), TIMER));
		reload();
	}

	private void reload() throws Exception {
		Method display = ConversationActivity.class.getDeclaredMethod(
				"displayMessages", Collection.class);
		display.setAccessible(true);
		display.invoke(activity,
				new ArrayList<ConversationMessageHeader>(headers));
		idle();
		layout();
	}

	@SuppressWarnings("unchecked")
	private void deleted(MessageId id) throws Exception {
		for (int i = headers.size() - 1; i >= 0; i--) {
			if (headers.get(i).getId().equals(id)) headers.remove(i);
		}
		Object viewModel = field(activity, "viewModel");
		Field f = ConversationViewModel.class.getDeclaredField(
				"messagesDeleted");
		f.setAccessible(true);
		((MutableLiveEvent<Collection<MessageId>>) f.get(viewModel))
				.setEvent(Collections.singletonList(id));
		idle();
		layout();
	}

	private RecyclerView list() throws Exception {
		return ((ZerionRecyclerView) field(activity, "list"))
				.getRecyclerView();
	}

	private void layout() throws Exception {
		RecyclerView list = list();
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
	}

	private RecyclerView.ViewHolder holder(MessageId id) throws Exception {
		layout();
		int position = adapter.getPositionOfMessage(id);
		assertTrue("the note is not listed", position >= 0);
		RecyclerView.ViewHolder h =
				list().findViewHolderForAdapterPosition(position);
		assertNotNull("no row for the note", h);
		return h;
	}

	private void reveal(MessageId id) throws Exception {
		holder(id).itemView.findViewById(R.id.layout).performClick();
		idle();
	}

	private long reveal(MessageId id, int seconds) throws Exception {
		View row = holder(id).itemView.findViewById(R.id.layout);
		row.performClick();
		long end = SystemClock.elapsedRealtime() + seconds * 1000L;
		idle();
		return end;
	}

	private void passUntil(long time) throws Exception {
		pass(time - SystemClock.elapsedRealtime());
	}

	private static int secondsLeft(long end) {
		long left = end - SystemClock.elapsedRealtime();
		return left <= 0 ? 0 : (int) ((left + 999) / 1000);
	}

	private String content(MessageId id) throws Exception {
		TextView v = holder(id).itemView.findViewById(R.id.secretContent);
		return v.getVisibility() == View.VISIBLE ? v.getText().toString()
				: "";
	}

	private String subtitle(MessageId id) throws Exception {
		TextView v = holder(id).itemView.findViewById(R.id.secretSubtitle);
		return v.getText().toString();
	}

	private String deletesIn(int seconds) {
		return activity.getString(R.string.secret_note_deletes_in, seconds);
	}

	private String expired() {
		return activity.getString(R.string.secret_note_expired);
	}

	private List<MessageId> burned() {
		return ProbeConversation.burned;
	}

	@Test
	public void aSecondNoteOpenedWhileTheFirstCountsDownStaysReadable()
			throws Exception {
		arrive(a, 1_000L, ConversationSecretNoteItem.wrapContent(FIRST, 5));
		arrive(b, 2_000L, ConversationSecretNoteItem.wrapContent(SECOND, 10));

		long endA = reveal(a, 5);
		pass(2_000);
		long endB = reveal(b, 10);

		assertEquals(Collections.emptyList(), burned());
		assertEquals(FIRST, content(a));
		assertEquals(SECOND, content(b));
		assertEquals(deletesIn(secondsLeft(endB)), subtitle(b));

		passUntil(endA);
		assertEquals(Collections.singletonList(a), burned());
		deleted(a);
		pass(500);

		assertEquals(Collections.singletonList(a), burned());
		assertEquals(SECOND, content(b));
		assertEquals(deletesIn(secondsLeft(endB)), subtitle(b));

		passUntil(endB - 500);
		assertEquals(Collections.singletonList(a), burned());
		assertEquals(SECOND, content(b));
		assertEquals(deletesIn(1), subtitle(b));

		passUntil(endB);
		assertEquals(Arrays.asList(a, b), burned());
		assertEquals("", content(b));
		assertEquals(expired(), subtitle(b));
	}

	@Test
	public void everyFurtherNoteGetsItsOwnCountdown() throws Exception {
		arrive(a, 1_000L, ConversationSecretNoteItem.wrapContent(FIRST, 3));
		arrive(b, 2_000L, ConversationSecretNoteItem.wrapContent(SECOND, 3));
		arrive(c, 3_000L, ConversationSecretNoteItem.wrapContent(THIRD, 60));

		long endA = reveal(a, 3);
		pass(1_000);
		long endB = reveal(b, 3);
		pass(1_000);
		long endC = reveal(c, 60);
		passUntil(endA);
		assertEquals(Collections.singletonList(a), burned());
		deleted(a);
		passUntil(endB);
		assertEquals(Arrays.asList(a, b), burned());
		deleted(b);
		pass(1_000);

		assertTrue(SystemClock.elapsedRealtime() < endC);
		assertEquals(Arrays.asList(a, b), burned());
		assertEquals(THIRD, content(c));
		assertEquals(deletesIn(secondsLeft(endC)), subtitle(c));
	}

	@Test
	public void reloadsAndShiftsWhileTwoNotesCountDownDeleteNothingEarly()
			throws Exception {
		arrive(a, 1_000L, ConversationSecretNoteItem.wrapContent(FIRST, 10));
		arrive(b, 2_000L, ConversationSecretNoteItem.wrapContent(SECOND, 10));
		reveal(a);
		reveal(b);
		long opened = SystemClock.elapsedRealtime();

		arrive(new MessageId(getRandomId()), 500L, "an older message");
		pass(500);
		reload();
		adapter.notifyItemChanged(adapter.getPositionOfMessage(a));
		adapter.notifyItemChanged(adapter.getPositionOfMessage(b));
		pass(500);
		adapter.notifyDataSetChanged();
		pass(500);
		long left = 10_000 - (SystemClock.elapsedRealtime() - opened);
		assertTrue(left > 0);

		assertEquals(Collections.emptyList(), burned());
		assertEquals(FIRST, content(a));
		assertEquals(SECOND, content(b));
		assertEquals(deletesIn((int) ((left + 999) / 1000)), subtitle(a));
		assertEquals(subtitle(a), subtitle(b));

		pass(left);
		assertEquals(2, burned().size());
		assertEquals("", content(a));
		assertEquals("", content(b));
	}

	@Test
	public void leavingTheChatBurnsEveryOpenedNoteAtOnce() throws Exception {
		arrive(a, 1_000L, ConversationSecretNoteItem.wrapContent(FIRST, 30));
		arrive(b, 2_000L, ConversationSecretNoteItem.wrapContent(SECOND, 60));
		arrive(c, 3_000L, ConversationSecretNoteItem.wrapContent(THIRD, 60));
		reveal(a);
		pass(1_000);
		reveal(b);
		TextView shownA = holder(a).itemView.findViewById(R.id.secretContent);
		TextView shownB = holder(b).itemView.findViewById(R.id.secretContent);
		assertEquals(FIRST, shownA.getText().toString());

		controller.pause().stop();

		assertEquals("", shownA.getText().toString());
		assertEquals("", shownB.getText().toString());
		assertEquals(2, burned().size());
		assertTrue(burned().containsAll(Arrays.asList(a, b)));
		pass(1_000);
		assertEquals(2, burned().size());

		controller.start().resume();
		idle();
		assertEquals("", content(a));
		assertEquals(expired(), subtitle(a));
		assertEquals("", content(b));
		assertEquals(expired(), subtitle(b));
		assertEquals(activity.getString(R.string.secret_note_tap_to_read),
				subtitle(c));
		pass(70_000);
		assertEquals(2, burned().size());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void noDeleteReachesTheModelBeforeANotesOwnDeadline()
			throws Exception {
		controller.pause().stop().destroy();
		open(BurningConversation.class);
		ConversationViewModel model = watchModel();
		List<Collection<MessageId>> deletes = new ArrayList<>();
		List<Long> deletedAt = new ArrayList<>();
		doAnswer(invocation -> {
			deletes.add(new ArrayList<>(
					(Collection<MessageId>) invocation.getArgument(0)));
			deletedAt.add(SystemClock.elapsedRealtime());
			return null;
		}).when(model).deleteMessages(any());
		arrive(a, 1_000L, ConversationSecretNoteItem.wrapContent(FIRST, 5));
		arrive(b, 2_000L, ConversationSecretNoteItem.wrapContent(SECOND, 10));

		long endA = reveal(a, 5);
		verify(model).startSecretNoteCountdown(a, 5);
		pass(2_000);
		long endB = reveal(b, 10);
		verify(model).startSecretNoteCountdown(b, 10);
		assertTrue(deletes.isEmpty());

		passUntil(endA);
		assertEquals(Collections.singletonList(Collections.singletonList(a)),
				deletes);
		assertTrue(deletedAt.get(0) >= endA);
		if (SystemClock.elapsedRealtime() < endB) {
			assertEquals(SECOND, content(b));
		}

		passUntil(endB);
		assertEquals(Arrays.asList(Collections.singletonList(a),
				Collections.singletonList(b)), deletes);
		assertTrue(deletedAt.get(1) >= endB);
		pass(20_000);
		assertEquals(2, deletes.size());
	}

	@Test
	public void aCountingNoteDeletedAnotherWayIsDroppedWithoutABurn()
			throws Exception {
		arrive(a, 1_000L, ConversationSecretNoteItem.wrapContent(FIRST, 30));
		arrive(b, 2_000L, ConversationSecretNoteItem.wrapContent(SECOND, 30));
		reveal(a);
		reveal(b);
		TextView shownA = holder(a).itemView.findViewById(R.id.secretContent);
		pass(1_000);

		deleted(a);

		assertEquals("", shownA.getText().toString());
		SecretNoteTimers timers = adapter.secretNotes();
		assertTrue(timers.isRevealed(a));
		assertEquals(null, timers.content(a));
		assertEquals(SECOND, content(b));

		fire("chatCleared", true);
		assertEquals(null, timers.content(b));
		assertTrue(timers.isRevealed(b));

		pass(40_000);
		assertEquals(Collections.emptyList(), burned());
	}
}
