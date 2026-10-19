package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.contact.ContactItem;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.view.ZerionRecyclerView;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChatReloadItemsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final ContactId CONTACT = new ContactId(5);

	public static class ProbeConversation extends ConversationActivity {

		static final List<MessageId> opened = new ArrayList<>();

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
			opened.add(messageId);
		}
	}

	private ProbeConversation activity;
	private ConversationAdapter adapter;
	private final GroupId group = new GroupId(getRandomId());
	private final MessageId note = new MessageId(getRandomId());

	private static Object field(Object o, String name) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Before
	public void setUp() throws Exception {
		ProbeConversation.opened.clear();
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, CONTACT.getInt());
		activity = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup().get();
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
	}

	private void display() throws Exception {
		show(note, ConversationSecretNoteItem.wrapContent("the plan", 1),
				false, false);
	}

	@SuppressWarnings("unchecked")
	private void show(MessageId id, String text, boolean local, boolean seen)
			throws Exception {
		Map<MessageId, String> texts =
				(Map<MessageId, String>) field(activity, "textCache");
		texts.put(id, text);
		PrivateMessageHeader h = new PrivateMessageHeader(id, group,
				1_000L, local, true, true, seen, true,
				Collections.emptyList(), -1L);
		Method display = ConversationActivity.class.getDeclaredMethod(
				"displayMessages", Collection.class);
		display.setAccessible(true);
		Collection<ConversationMessageHeader> headers =
				Collections.<ConversationMessageHeader>singletonList(h);
		display.invoke(activity, headers);
		idle();
	}

	private List<String> watch() {
		List<String> changes = new ArrayList<>();
		adapter.registerAdapterDataObserver(
				new RecyclerView.AdapterDataObserver() {
					@Override
					public void onChanged() {
						changes.add("all");
					}

					@Override
					public void onItemRangeChanged(int start, int count) {
						changes.add("changed " + start + "+" + count);
					}

					@Override
					public void onItemRangeChanged(int start, int count,
							@Nullable Object payload) {
						changes.add("changed " + start + "+" + count
								+ (payload == null ? "" : " partly"));
					}

					@Override
					public void onItemRangeInserted(int start, int count) {
						changes.add("inserted " + start + "+" + count);
					}

					@Override
					public void onItemRangeRemoved(int start, int count) {
						changes.add("removed " + start + "+" + count);
					}
				});
		return changes;
	}

	@SuppressWarnings("unchecked")
	private MutableLiveData<ContactItem> shownContact() throws Exception {
		Object viewModel = field(activity, "viewModel");
		Field f = ConversationViewModel.class.getDeclaredField("contactItem");
		f.setAccessible(true);
		return (MutableLiveData<ContactItem>) f.get(viewModel);
	}

	private static ContactItem contact(Author author,
			@Nullable String alias) {
		return new ContactItem(new Contact(CONTACT, author,
				new AuthorId(getRandomId()), alias, null, false),
				new AuthorInfo(AuthorInfo.Status.UNVERIFIED));
	}

	@Test
	public void renamingTheContactDoesNotStopANoteBeingRead()
			throws Exception {
		MutableLiveData<ContactItem> shown = shownContact();
		Author author = getAuthor();
		shown.setValue(contact(author, null));
		idle();
		display();
		ConversationSecretNoteItem first =
				(ConversationSecretNoteItem) adapter.getItemAt(0);
		assertNotNull(first);
		RecyclerView.ViewHolder h = holder(0);
		h.itemView.findViewById(R.id.layout).performClick();
		idle();
		assertTrue(first.isRevealed());

		shown.setValue(contact(author, "Ally"));
		idle();

		assertSame("the rename swapped the row that is counting down", h,
				holder(0));
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
		assertNull("the plaintext outlived the countdown", first.getText());
		assertTrue(ProbeConversation.opened.contains(note));
	}

	@Test
	public void aReloadLeavesAMessageWithAReactionAlone() throws Exception {
		MessageId m = new MessageId(getRandomId());
		show(m, "hello", true, false);
		ConversationItem item = adapter.getItemAt(0);
		assertNotNull(item);
		item.addReaction("ok");
		List<String> changes = watch();

		show(m, "hello", true, false);

		assertSame(item, adapter.getItemAt(0));
		assertTrue(item.hasReactions());
		assertEquals(Collections.emptyList(), changes);
	}

	@Test
	public void aReloadThatOnlyDeliversAMessageRefreshesItsStateLine()
			throws Exception {
		MessageId m = new MessageId(getRandomId());
		show(m, "hello", true, false);
		ConversationItem item = adapter.getItemAt(0);
		assertNotNull(item);
		List<String> changes = watch();

		show(m, "hello", true, true);

		assertSame(item, adapter.getItemAt(0));
		assertTrue(item.isSeen());
		assertEquals(Collections.singletonList("changed 0+1 partly"), changes);
	}

	@Test
	public void aReloadWithNewTextRebindsTheMessage() throws Exception {
		MessageId m = new MessageId(getRandomId());
		show(m, "hello", true, false);
		List<String> changes = watch();

		show(m, "hello again", true, false);

		assertEquals("hello again", adapter.getItemAt(0).getText());
		assertEquals(Collections.singletonList("changed 0+1"), changes);
	}

	private RecyclerView.ViewHolder holder(int position) throws Exception {
		RecyclerView list = ((ZerionRecyclerView) field(activity, "list"))
				.getRecyclerView();
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
	}

	@Test
	public void aReloadKeepsTheRevealedNoteAndItsWipeReachesTheList()
			throws Exception {
		display();
		ConversationSecretNoteItem first =
				(ConversationSecretNoteItem) adapter.getItemAt(0);
		assertNotNull(first);
		RecyclerView.ViewHolder h = holder(0);
		h.itemView.findViewById(R.id.layout).performClick();
		idle();
		assertTrue(first.isRevealed());

		display();

		assertEquals(1, adapter.getItemCount());
		ConversationSecretNoteItem listed =
				(ConversationSecretNoteItem) adapter.getItemAt(0);
		assertSame("the reload replaced the note the holder is burning",
				first, listed);
		assertTrue(listed.isRevealed());
		assertSame("the reload disturbed the row that is counting down", h,
				holder(0));
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
		assertNull("the plaintext outlived the countdown", listed.getText());
		assertNull(first.getText());
	}

	@Test
	public void aNoteArrivingAgainWhileItIsReadKeepsTheShownNote()
			throws Exception {
		display();
		ConversationSecretNoteItem first =
				(ConversationSecretNoteItem) adapter.getItemAt(0);
		assertNotNull(first);
		RecyclerView.ViewHolder h = holder(0);
		h.itemView.findViewById(R.id.layout).performClick();
		idle();
		assertTrue(first.isRevealed());

		for (int way = 0; way < 3; way++) {
			arriveAgain(way);
			assertEquals(1, adapter.getItemCount());
			assertSame("arrival " + way + " replaced the note being read",
					first, adapter.getItemAt(0));
			assertTrue(first.isRevealed());
		}

		assertSame(h, holder(0));
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
		assertNull("the plaintext outlived the countdown", first.getText());
	}

	@Test
	public void aNoteArrivingAgainAfterItWasReadStaysWiped() throws Exception {
		display();
		ConversationSecretNoteItem first =
				(ConversationSecretNoteItem) adapter.getItemAt(0);
		assertNotNull(first);
		holder(0).itemView.findViewById(R.id.layout).performClick();
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
		assertNull(first.getText());

		for (int way = 0; way < 3; way++) {
			arriveAgain(way);
			ConversationItem listed = adapter.getItemAt(0);
			assertSame("arrival " + way + " replaced the read note", first,
					listed);
			assertTrue(first.isRevealed());
			assertNull("arrival " + way + " gave the note its text back",
					first.getText());
			TextView subtitle =
					holder(0).itemView.findViewById(R.id.secretSubtitle);
			assertNotEquals(activity.getString(
					R.string.secret_note_tap_to_read),
					subtitle.getText().toString());
		}
	}

	private PrivateMessageHeader noteHeader() {
		return new PrivateMessageHeader(note, group, 1_000L, false, true,
				true, false, true, Collections.emptyList(), -1L);
	}

	@SuppressWarnings("unchecked")
	private void arriveAgain(int way) throws Exception {
		String text = ConversationSecretNoteItem.wrapContent("the plan", 1);
		Map<MessageId, String> texts =
				(Map<MessageId, String>) field(activity, "textCache");
		texts.put(note, text);
		if (way == 0) {
			ConversationSecretNoteItem again = new ConversationSecretNoteItem(
					noteHeader(), new MutableLiveData<>("Bob"));
			again.setText(text);
			Method add = ConversationActivity.class.getDeclaredMethod(
					"addConversationItem", ConversationItem.class);
			add.setAccessible(true);
			add.invoke(activity, again);
		} else if (way == 1) {
			Method added = ConversationActivity.class.getDeclaredMethod(
					"onAddedPrivateMessage", PrivateMessageHeader.class);
			added.setAccessible(true);
			added.invoke(activity, noteHeader());
		} else {
			Object viewModel = field(activity, "viewModel");
			Field f = ConversationViewModel.class.getDeclaredField(
					"secretNoteAdded");
			f.setAccessible(true);
			((MutableLiveEvent<Pair<PrivateMessageHeader, String>>)
					f.get(viewModel)).setEvent(new Pair<>(noteHeader(), text));
		}
		idle();
	}

	@Test
	public void aReloadDoesNotHandTheWipedNoteItsTextBack() throws Exception {
		display();
		ConversationSecretNoteItem first =
				(ConversationSecretNoteItem) adapter.getItemAt(0);
		assertNotNull(first);
		holder(0).itemView.findViewById(R.id.layout).performClick();
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
		assertNull(first.getText());

		assertEquals(Collections.singletonList(note), ProbeConversation.opened);

		display();

		assertEquals(1, adapter.getItemCount());
		ConversationItem listed = adapter.getItemAt(0);
		assertSame("the reload replaced the read note", first, listed);
		assertTrue(first.isRevealed());
		assertNull("the reload gave the read note its text back",
				first.getText());
	}
}
