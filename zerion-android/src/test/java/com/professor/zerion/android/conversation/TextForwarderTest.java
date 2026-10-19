package com.professor.zerion.android.conversation;

import org.junit.Before;
import org.junit.Test;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.system.AndroidExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;

public class TextForwarderTest {

	private final TransactionManager db = mock(TransactionManager.class);
	private final ContactManager contacts = mock(ContactManager.class);
	private final MessagingManager messaging = mock(MessagingManager.class);
	private final ConversationManager conversations =
			mock(ConversationManager.class);
	private final AutoDeleteManager autoDelete = mock(AutoDeleteManager.class);
	private final PrivateMessageFactory factory =
			mock(PrivateMessageFactory.class);
	private final AndroidExecutor ui = mock(AndroidExecutor.class);
	private final Transaction txn = mock(Transaction.class);
	private final ContactId to = new ContactId(7);
	private final GroupId group = new GroupId(new byte[32]);
	private final PrivateMessage built = mock(PrivateMessage.class);
	private TextForwarder forwarder;
	private final List<String> results = new ArrayList<>();

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception {
		doAnswer(i -> {
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ui).runOnUiThread(any(Runnable.class));
		doAnswer(i -> {
			((DbRunnable<Exception>) i.getArgument(1)).run(txn);
			return null;
		}).when(db).transaction(eq(false), any());
		forwarder = new TextForwarder(db, mock(LifecycleManager.class),
				contacts, messaging, conversations, autoDelete, factory,
				Runnable::run, ui);
		when(messaging.getConversationId(txn, to)).thenReturn(group);
		when(conversations.getTimestampForOutgoingMessage(txn, to))
				.thenReturn(5_000L);
		when(messaging.getContactMessageFormat(txn, to)).thenReturn(
				PrivateMessageFormat.TEXT_IMAGES_SALTED);
		when(autoDelete.getAutoDeleteTimer(eq(txn), eq(to), anyLong()))
				.thenReturn(-1L);
		when(factory.createSaltedPrivateMessage(any(), anyLong(), anyString(),
				any(), anyLong(), any())).thenReturn(built);
	}

	@Test
	public void forwardingStoresANewMessageForTheChosenContact()
			throws Exception {
		forwarder.forward(to, "hello", () -> results.add("ok"),
				() -> results.add("failed"));

		verify(factory).createSaltedPrivateMessage(eq(group), eq(5_000L),
				eq("hello"), any(), anyLong(), eq(null));
		verify(messaging).addLocalMessage(txn, built);
		assertEquals(Arrays.asList("ok"), results);
	}

	@Test
	public void aFailedForwardSaysSo() throws Exception {
		doThrow(new DbException()).when(messaging).addLocalMessage(txn, built);

		forwarder.forward(to, "hello", () -> results.add("ok"),
				() -> results.add("failed"));

		assertEquals(Arrays.asList("failed"), results);
	}

	@Test
	public void theOpenChatIsNotOfferedAsATarget() throws Exception {
		Contact open = contact(5);
		Contact other = contact(7);
		when(contacts.getContacts()).thenReturn(Arrays.asList(open, other));
		List<List<Contact>> seen = new ArrayList<>();

		forwarder.loadContacts(new ContactId(5), seen::add);

		assertEquals(1, seen.size());
		assertEquals(1, seen.get(0).size());
		assertSame(other, seen.get(0).get(0));
	}

	@Test
	public void everyContactIsOfferedFromAGroup() throws Exception {
		Contact a = contact(5);
		Contact b = contact(7);
		when(contacts.getContacts()).thenReturn(Arrays.asList(a, b));
		List<List<Contact>> seen = new ArrayList<>();

		forwarder.loadContacts(null, seen::add);

		assertEquals(Arrays.asList(a, b), seen.get(0));
	}

	@Test
	public void contactsThatCannotBeReadGiveNoList() throws Exception {
		when(contacts.getContacts()).thenThrow(new DbException());
		List<List<Contact>> seen = new ArrayList<>();

		forwarder.loadContacts(null, seen::add);

		assertEquals(1, seen.size());
		assertNull(seen.get(0));
		verify(messaging, never()).addLocalMessage(any(), any());
	}

	@Test
	public void longTextIsCutToWhatOneMessageHolds() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < MAX_PRIVATE_MESSAGE_TEXT_LENGTH + 10; i++) {
			sb.append('a');
		}
		assertEquals(MAX_PRIVATE_MESSAGE_TEXT_LENGTH,
				TextForwarder.limit(sb.toString()).length());
		assertEquals("short", TextForwarder.limit("short"));
	}

	private static Contact contact(int id) {
		Contact c = mock(Contact.class);
		when(c.getId()).thenReturn(new ContactId(id));
		return c;
	}
}
