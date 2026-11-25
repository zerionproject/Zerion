package com.professor.zerion.android.conversation.voice;

import org.junit.Test;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.UserMessageMarker;
import org.zerionproject.core.api.sync.event.MessagesSentEvent;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class VoiceMemoLaterPartTest {

	private final ContactId contact = new ContactId(7);
	private final GroupId group = new GroupId(id(1));
	private final List<String> steps = new ArrayList<>();
	private final List<MessageId> stored = new ArrayList<>();
	private byte nextId = 10;

	@Test
	public void eachLaterPartIsStoredAndThenNotedAsALaterPart() {
		VoiceMessageSendManager manager = manager();
		MessageId first = new MessageId(id(2));
		manager.register(contact, group, "0011223344556677",
				Arrays.asList("part one", "part two"), first);

		manager.eventOccurred(new MessagesSentEvent(contact,
				Collections.singletonList(first), 10L));

		assertEquals(Arrays.asList("stored part one", "later part"), steps);
		steps.clear();
		manager.eventOccurred(new MessagesSentEvent(contact,
				Collections.singletonList(stored.get(0)), 10L));

		assertEquals(Arrays.asList("stored part two", "later part"), steps);
		assertEquals(2, stored.size());
	}

	@Test
	public void aMessageThatIsNoPartOfAMemoIsNotNoted() {
		VoiceMessageSendManager manager = manager();

		manager.eventOccurred(new MessagesSentEvent(contact,
				Collections.singletonList(new MessageId(id(3))), 10L));

		assertEquals(Collections.emptyList(), steps);
	}

	private VoiceMessageSendManager manager() {
		TransactionManager db = fake(TransactionManager.class,
				(name, args) -> {
					if (!name.equals("transactionWithResult")) {
						throw new AssertionError(name);
					}
					return ((DbCallable<?, ?>) args[1])
							.call(new Transaction(null, false));
				});
		MessagingManager messaging = fake(MessagingManager.class,
				(name, args) -> {
					if (!name.equals("addLocalMessage")) {
						throw new AssertionError(name);
					}
					PrivateMessage pm = (PrivateMessage) args[1];
					stored.add(pm.getMessage().getId());
					steps.add("stored " + new String(
							pm.getMessage().getBody(), "UTF-8"));
					return null;
				});
		PrivateMessageFactory factory = fake(PrivateMessageFactory.class,
				(name, args) -> new PrivateMessage(new Message(
						new MessageId(id(nextId++)), (GroupId) args[0],
						(Long) args[1], ((String) args[2]).getBytes("UTF-8"))));
		ConversationManager conversations = fake(ConversationManager.class,
				(name, args) -> 1_000L);
		UserMessageMarker marker = new UserMessageMarker() {
			@Override
			public void noteUserMessage(MessageId id) {
				steps.add("marked");
			}

			@Override
			public void noteLaterPart(MessageId id) {
				assertEquals(stored.get(stored.size() - 1), id);
				steps.add("later part");
			}
		};
		return new VoiceMessageSendManager(
				fake(EventBus.class, (name, args) -> null), Runnable::run,
				db, messaging, factory, conversations,
				new VoiceChunkAssembler(), marker);
	}

	private interface Handler {
		Object handle(String name, Object[] args) throws Exception;
	}

	@SuppressWarnings("unchecked")
	private static <T> T fake(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type},
				(proxy, method, args) -> handler.handle(method.getName(),
						args));
	}

	private static byte[] id(int fill) {
		byte[] b = new byte[32];
		Arrays.fill(b, (byte) fill);
		return b;
	}
}
