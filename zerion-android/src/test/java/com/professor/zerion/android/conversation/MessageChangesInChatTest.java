package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Looper;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.view.TextInputView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.LinkPreview;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.app.api.messaging.event.PrivateMessageEditedEvent;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

	private ActivityController<ProbeConversation> controller;
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
		controller = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup();
		activity = controller.get();
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
		viewModel = (ConversationViewModel) field(activity, "viewModel");
	}

	@SuppressWarnings("unchecked")
	private <T> T inViewModel(String name) throws Exception {
		Field f = ConversationViewModel.class.getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(viewModel);
	}

	private void display(Collection<ConversationMessageHeader> headers)
			throws Exception {
		Method display = ConversationActivity.class.getDeclaredMethod(
				"displayMessages", Collection.class);
		display.setAccessible(true);
		display.invoke(activity, headers);
		idle();
	}

	private void recreate() throws Exception {
		controller.recreate();
		activity = controller.get();
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
		assertSame(viewModel, field(activity, "viewModel"));
	}

	@Nullable
	private ConversationItem shown(MessageId id) {
		for (int i = 0; i < adapter.getItemCount(); i++) {
			ConversationItem item = adapter.getItemAt(i);
			if (item != null && id.equals(item.getId())) return item;
		}
		return null;
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
	@SuppressWarnings("unchecked")
	public void anEditLeavesNoEarlierTextAnywhereInTheChat() throws Exception {
		MessageId original = new MessageId(getRandomId());
		MessageId reply = new MessageId(getRandomId());
		ConversationMessageItem o =
				item(header(original, 1_000L, false), "Hey Allfs");
		adapter.add(o);
		ConversationMessageItem r = item(header(reply, 2_000L, true), "ok");
		r.setReplyToMessageId(original);
		r.setReplyToText("Hey Allfs");
		adapter.add(r);
		Map<MessageId, String> loaded = new HashMap<>();
		loaded.put(original, "Hey Allfs");
		loaded.put(reply, "ok");
		MutableLiveData<Map<MessageId, String>> texts =
				inViewModel("messageTexts");
		texts.setValue(loaded);
		Map<MessageId, Pair<MessageId, String>> quotes =
				inViewModel("replyContextMap");
		quotes.put(reply, new Pair<>(original, "Hey Allfs"));
		idle();
		TextInputView input = (TextInputView) field(activity, "textInputView");
		input.showReplyPreview(o);
		idle();

		viewModel.eventOccurred(new PrivateMessageEditedEvent(CONTACT,
				original, "Hey all", false));
		idle();

		assertEquals("Hey all", texts.getValue().get(original));
		assertFalse(texts.getValue().containsValue("Hey Allfs"));
		Map<MessageId, String> cache =
				(Map<MessageId, String>) field(activity, "textCache");
		assertEquals("Hey all", cache.get(original));
		assertFalse(cache.containsValue("Hey Allfs"));
		assertEquals("Hey all", quotes.get(reply).getSecond());
		assertEquals("Hey all", r.getReplyToText());
		TextView preview = input.findViewById(R.id.reply_text);
		assertEquals("Hey all", preview.getText().toString());
		assertTrue(search("Allfs").isEmpty());
		assertEquals(Collections.singletonList(shownIds().indexOf(original)),
				search("Hey all"));
	}

	@Test
	public void anEditLeavesNoEarlierTextInTheConversationCache() {
		MessageId original = new MessageId(getRandomId());
		ConversationCache cache = ConversationCache.getInstance();
		cache.put(CONTACT, Collections.<ConversationMessageHeader>singletonList(
				header(original, 1_000L, false)),
				Collections.singletonMap(original, "Hey Allfs"));
		DeletedContentEvictor evictor = new DeletedContentEvictor(
				new com.professor.zerion.android.conversation.voice
						.VoiceChunkAssembler(), () -> {
		});

		evictor.eventOccurred(new PrivateMessageEditedEvent(CONTACT,
				original, "Hey all", false));

		assertFalse(cache.getCachedTexts(CONTACT)
				.containsValue("Hey Allfs"));
	}

	@SuppressWarnings("unchecked")
	private List<Integer> search(String query) throws Exception {
		Method search = ConversationActivity.class.getDeclaredMethod(
				"performMessageSearch", String.class);
		search.setAccessible(true);
		search.invoke(activity, query);
		idle();
		return new ArrayList<>(
				(List<Integer>) field(activity, "searchMatchPositions"));
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
	public void aStaleLoadDoesNotBringBackTheTextOfADeletedQuote()
			throws Exception {
		MessageId gone = new MessageId(getRandomId());
		MessageId reply = new MessageId(getRandomId());
		PrivateMessageHeader replyHeader = new PrivateMessageHeader(reply,
				group, 2_000L, true, true, true, false, true,
				Collections.emptyList(), -1L, gone, false);
		Map<MessageId, Boolean> local = inViewModel("localById");
		local.put(gone, false);
		local.put(reply, true);

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				Collections.singletonList(gone)));
		idle();
		Map<MessageId, String> staleTexts = new HashMap<>();
		staleTexts.put(gone, "secret plan");
		staleTexts.put(reply, "ok");
		MutableLiveData<Map<MessageId, String>> texts =
				inViewModel("messageTexts");
		texts.setValue(staleTexts);
		idle();
		display(Arrays.asList(replyHeader, header(gone, 1_000L, false)));

		assertNull(shown(gone));
		ConversationItem r = shown(reply);
		assertNotNull(r);
		assertEquals(activity.getString(R.string.reply_original_unavailable),
				r.getReplyToText());
	}

	@Test
	public void reopeningTheChatShowsNeitherDeletedNorReplacedText()
			throws Exception {
		MessageId gone = new MessageId(getRandomId());
		MessageId changed = new MessageId(getRandomId());
		Map<MessageId, String> staleTexts = new HashMap<>();
		staleTexts.put(gone, "secret plan");
		staleTexts.put(changed, "Hey Allfs");
		MutableLiveData<Map<MessageId, String>> texts =
				inViewModel("messageTexts");
		MutableLiveData<Collection<ConversationMessageHeader>> headers =
				inViewModel("messageHeaders");
		texts.setValue(staleTexts);
		headers.setValue(Arrays.asList(header(changed, 2_000L, false),
				header(gone, 1_000L, false)));
		idle();

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				Collections.singletonList(gone)));
		viewModel.eventOccurred(new PrivateMessageEditedEvent(CONTACT,
				changed, "Hey all", false));
		idle();
		recreate();

		assertNull(shown(gone));
		ConversationMessageItem c = (ConversationMessageItem) shown(changed);
		assertNotNull(c);
		assertEquals("Hey all", c.getText());
		assertTrue(c.isEdited());
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

	@Test
	public void aDeletedMessageLeavesNothingInTheViewModel()
			throws Exception {
		MessageId gone = new MessageId(getRandomId());
		MessageId kept = new MessageId(getRandomId());
		MessageId replyToGone = new MessageId(getRandomId());
		Map<MessageId, String> texts = new HashMap<>();
		texts.put(gone, "secret plan");
		texts.put(kept, "hello");
		MutableLiveData<Map<MessageId, String>> textData =
				inViewModel("messageTexts");
		textData.setValue(texts);
		Map<MessageId, Map<String, Integer>> reactions = new HashMap<>();
		reactions.put(gone, Collections.singletonMap("heart", 1));
		reactions.put(kept, Collections.singletonMap("heart", 1));
		MutableLiveData<Map<MessageId, Map<String, Integer>>> reactionData =
				inViewModel("reactionsMap");
		reactionData.setValue(reactions);
		Map<MessageId, LinkPreview> previews = new HashMap<>();
		previews.put(gone, new LinkPreview("https://example.org", "Plan",
				null, null));
		MutableLiveData<Map<MessageId, LinkPreview>> previewData =
				inViewModel("linkPreviewsMap");
		previewData.setValue(previews);
		Map<MessageId, Pair<MessageId, String>> quotes =
				inViewModel("replyContextMap");
		quotes.put(replyToGone, new Pair<>(gone, "secret plan"));
		quotes.put(gone, new Pair<>(kept, "hello"));
		Map<MessageId, String> edited = inViewModel("editedTexts");
		edited.put(gone, "secret plan v2");
		idle();

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				Collections.singletonList(gone)));
		idle();

		assertFalse(textData.getValue().containsKey(gone));
		assertTrue(textData.getValue().containsKey(kept));
		assertFalse(reactionData.getValue().containsKey(gone));
		assertTrue(reactionData.getValue().containsKey(kept));
		assertFalse(previewData.getValue().containsKey(gone));
		assertTrue(quotes.isEmpty());
		assertFalse(edited.containsKey(gone));
	}

	@Test
	public void clearingTheChatLeavesNothingInTheViewModel()
			throws Exception {
		MessageId gone = new MessageId(getRandomId());
		MutableLiveData<Map<MessageId, Map<String, Integer>>> reactionData =
				inViewModel("reactionsMap");
		reactionData.setValue(Collections.singletonMap(gone,
				Collections.singletonMap("heart", 1)));
		MutableLiveData<Map<MessageId, LinkPreview>> previewData =
				inViewModel("linkPreviewsMap");
		previewData.setValue(Collections.singletonMap(gone,
				new LinkPreview("https://example.org", "Plan", null, null)));
		Map<MessageId, Pair<MessageId, String>> quotes =
				inViewModel("replyContextMap");
		quotes.put(new MessageId(getRandomId()), new Pair<>(gone, "plan"));
		Map<MessageId, String> edited = inViewModel("editedTexts");
		edited.put(gone, "secret plan v2");

		Method evict = ConversationViewModel.class.getDeclaredMethod(
				"evictConversation", ContactId.class);
		evict.setAccessible(true);
		evict.invoke(viewModel, CONTACT);
		idle();

		assertTrue(reactionData.getValue().isEmpty());
		assertTrue(previewData.getValue().isEmpty());
		assertTrue(quotes.isEmpty());
		assertTrue(edited.isEmpty());
	}
}
