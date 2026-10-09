package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.app.api.messaging.event.PrivateMessageEditedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MessageChangesInChatTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final ContactId CONTACT = new ContactId(5);

	public static class ProbeConversation extends ConversationActivity {

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

	private ProbeConversation activity;
	private ConversationAdapter adapter;
	private ConversationViewModel viewModel;
	private final GroupId group = new GroupId(getRandomId());

	private static Object field(Object o, String name) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private PrivateMessageHeader header(MessageId id, long time,
			boolean local) {
		return new PrivateMessageHeader(id, group, time, local, true, true,
				false, true, Collections.emptyList(), -1L);
	}

	private ConversationMessageItem item(PrivateMessageHeader h, String text) {
		ConversationMessageItem item = new ConversationMessageItem(
				h.isLocal() ? R.layout.list_item_conversation_msg_out
						: R.layout.list_item_conversation_msg_in, h,
				new MutableLiveData<>("Bob"));
		item.setText(text);
		return item;
	}

	@Before
	public void setUp() throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, CONTACT.getInt());
		activity = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup().get();
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
		viewModel = (ConversationViewModel) field(activity, "viewModel");
	}

	private List<MessageId> shownIds() {
		List<MessageId> ids = new ArrayList<>();
		for (int i = 0; i < adapter.getItemCount(); i++) {
			ids.add(adapter.getItemAt(i).getId());
		}
		return ids;
	}

	@Test
	public void aQuoteOfADeletedMessageNoLongerShowsItsText() {
		MessageId original = new MessageId(getRandomId());
		MessageId reply = new MessageId(getRandomId());
		adapter.add(item(header(original, 1_000L, false), "secret plan"));
		ConversationMessageItem r = item(header(reply, 2_000L, true), "ok");
		r.setReplyToMessageId(original);
		r.setReplyToText("secret plan");
		adapter.add(r);
		idle();

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				Collections.singletonList(original)));
		idle();

		assertFalse(shownIds().contains(original));
		assertEquals(activity.getString(R.string.reply_original_unavailable),
				r.getReplyToText());
	}

	@Test
	public void anEditedMessageAndItsQuotesSaySo() {
		MessageId original = new MessageId(getRandomId());
		MessageId reply = new MessageId(getRandomId());
		ConversationMessageItem o =
				item(header(original, 1_000L, false), "Hey Allfs");
		adapter.add(o);
		ConversationMessageItem r = item(header(reply, 2_000L, true), "ok");
		r.setReplyToMessageId(original);
		r.setReplyToText("Hey Allfs");
		adapter.add(r);
		idle();

		viewModel.eventOccurred(new PrivateMessageEditedEvent(CONTACT,
				original, "Hey all", false));
		idle();

		assertEquals("Hey all", o.getText());
		assertTrue(o.isEdited());
		assertEquals("Hey all", r.getReplyToText());
		assertTrue(r.isReplyToEdited());
	}

	@Test
	public void deletionsThatArriveTogetherAreAllApplied() {
		MessageId a = new MessageId(getRandomId());
		MessageId b = new MessageId(getRandomId());
		adapter.add(item(header(a, 1_000L, false), "a"));
		adapter.add(item(header(b, 2_000L, false), "b"));
		idle();

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				Collections.singletonList(a)));
		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				Collections.singletonList(b)));
		idle();

		assertTrue(shownIds().isEmpty());
	}

	@Test
	public void aReloadStartedBeforeADeleteDoesNotBringItBack()
			throws Exception {
		MessageId kept = new MessageId(getRandomId());
		MessageId gone = new MessageId(getRandomId());
		Collection<ConversationMessageHeader> staleLoad = Arrays.asList(
				header(kept, 1_000L, false), header(gone, 2_000L, false));

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				Collections.singletonList(gone)));
		idle();
		Method display = ConversationActivity.class.getDeclaredMethod(
				"displayMessages", Collection.class);
		display.setAccessible(true);
		display.invoke(activity, staleLoad);
		idle();

		assertTrue(shownIds().contains(kept));
		assertFalse(shownIds().contains(gone));
	}
}
